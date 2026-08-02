# Artemis Sentinel — Handoff: v2.4.x shipped → next phase: WiFi helper + Bluetooth helper

> Written 2026-08-02. Read `docs/AGENTS.md` first (project reference),
> then `docs/SECURITY.md`, then this handoff. Phases: this file is the
> brief for the NEXT agent. Docs = AGENTS.md + SECURITY.md + this file.

---

## 0. PHASE BRIEF (what the next agent builds) — WiFi helper + Bluetooth helper

Two new **FGS-only helper services** that expose phone state to the dashboard
over the existing protected HTTP/TLS surface. Do NOT touch the frozen
auth/security sector (AGENTS.md §3.1). Volume control is ALREADY DONE (see
§1, v2.4.4). The heat/offload item (§3) stays open and independent.

### 0.A WiFi helper — scan + connect info + strength
Intent: dashboard shows current SSID / RSSI / link speed, and lists nearby
APs so the operator can see where the phone is and whether its network is
strong.
- New Kotlin file under `feature/` (e.g. `WifiHelperService.kt`), FGS-only,
  no Activity, no AndroidX.Compose.
- Read current connection: `WifiManager` (API ≤28, grantable permission) or
  the modern `WifiManager.connectionInfo` with `ACCESS_FINE_LOCATION`
  (API 29+ needs the coarse/fine location permission — grant via
  `pm grant ... ACCESS_FINE_LOCATION`; Samsung accepts it).
- Scan: `startScan()` + `onReceive(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)`.
  Return list of `{ssid, bssid, rssi, freqHz, security}`.
- Serialize as a `0xNN`/JSON command on the existing WS or a plain REST
  endpoint `GET /wifi/info`, `GET /wifi/scan`. Follow the existing
  command-routing pattern in `SimpleHttpServer.kt` (`3345`-style `when`)
  and the existing `device_client`+routes in `dashboard_web/server`.
- Mind the phone `HttpURLConnection` requires `\r\n` (existing pitfall).

### 0.B. Bluetooth helper — paired devices + strength
Intent: list paired BT devices (name, MAC, class) so a leash/headset
proximity can be sanity-checked.
- `BluetoothAdapter` (system API, no runtime perm for *discovery* results it
  does for scan/q strings) — `getBondedDevices()`, plus optionally query
  connected profile `getProfileConnectionState()`.
- Android 12 (M51) is fine with `BluetoothAdapter` from a bound service;
  `BLUETOOTH_CONNECT` is a `dangerous` normal permission and is
  `pm grant`-able on Samsung like `WRITE_CALL_LOG`.
- Return list JSON: `[{name, address, type, state}]`. Same endpoint style.
- Dashboard: a small read-only panel card (not live view) that calls the
  helper and renders the arrays.

### 0.C Restraint
- Everything is read-only inspect; no pair/unpair, no connect, no power
  toggle unless the user explicitly asks. Keep the frozen security surface
  intact (metadata / body are trusted-envelope secured already).

## 1. SHIPPED THIS RUN — front camera, audio cutouts + speech post-processing, grid, volume

All pushed to GitHub (`origin/master` == `HEAD`), tree clean:

- `6b3412e` **v2.4.3** — front-camera fix: `ws/live` `pip` command handler
  in `SimpleHttpServer.kt` hard-coded `LENS_FACING_FRONT`; PiP now binds
  the **selected** lens (`st.camLens` 0x03 front / 0x02 rear), so FLIP no
  longer fights the wrong binding. LIVE-verified on M51: both lenses stream
  distinct 1088×1088 square feeds, label toggles FRONT/REAR correctly.
- `d36eabf` **v2.4.4** — audio + grid + volume:
  * **Audio cutouts fixed** (browser): `pushLiveAudio` now primes a 250ms
    playout buffer before connecting the ScriptProcessor, and a decaying
    tail fills transient under-runs instead of an abrupt zero "click". The
    ms cutouts were cold-queue starts + burst jitter; both are mitigated.
  * **Python server noise reduction + speech focus** (NEW — pure stdlib):
    `dashboard_web/server/speech_dsp.py` — `SpeechNoiseReducer` — HP 170 Hz
    (rumble/mains) + LP 5.6kHz (hiss) + presence peak 1.9kHz (speech
    formants) + adaptive noise gate (running RMS floor). Wired into the
    `ws/live` relay in `dashboard_web/server/__init__.py` via
    `_process_live_frame()`: intercepts channel-4 PCM16 frames before they
    reach the browser. Phone stays stateless; host does the DSP (fits the
    offload goal). ~28ms per second-of-audio real → won't stall the relay.
  * **Grid scaling fixed**: right control column is now `w-[220px] shrink-0`
    at ALL breakpoints (was `lg:w-[220px]`, which let it stretch below
    `lg`). Grid stays a fixed 220px hugging the screen feed edge, no
    ballooning on window resize.
  * **Volume control on dashboard**: slider row under the control grid
    (`volume_down` icon, range 0–100, % readout, enabled when live view
    runs). Drives a WebAudio `GainNode` in the output chain.
- `f66b6c9` **v2.4.4b** — drop the unused phone `volume` WS command:
  dashboard volume is WebAudio gain only (device media volume doesn't
  affect the mic uplink), so the extra command was dead. Cleaned.

## 2. LIVE VIEW pipeline — current phone-side HEAT sources (carried, independent)

`ws/live` (phone = WS server) streams three channels on one socket:
`0x01` screen JPEG, `0x02`/`0x03` camera (rear/front) JPEG, `0x04` audio.

Phone does ALL image encode on CPU — this is the overheating:
- **Screen** [feature/RemoteControlService.kt `capture()`]: full-res
  `takeScreenshot` → `Bitmap.wrapHardwareBuffer` → `compress(JPEG, 80)`.
- **Camera** [feature/CameraController.kt `startPreviewStream` +
  `nv21ToJpeg`]: CameraX ImageAnalysis 640×480 → `imageToNv21` (plane
  walk) + `YuvImage.compressToJpeg(..., 55)` EVERY frame, plus an
  NV21→Bitmap→rotate→re-encode when rotation≠0 (M51 front is square; rear
  4:3).
- **WS loop** [SimpleHttpServer.kt `handleLiveWs`] interleaves both at up
  to ~16 fps (`minFrameGap=60`).
- Host: NVIDIA RTX 3050 (NVENC capable) present; NO ffmpeg, NO opencv.

Do the heat-optimization work (3.B) independently of, and after, the
WiFi/BT helper work — it requires live A/B on the M51 (hot, >42°C during
stream tests → short test windows only).

## 3. NEXT phase — heat / offload (pick ONE, confirmed priority)

Full "raw NV21 + raw RGBA to server GPU (NVENC)" is large and at its
heaviest pushes ~7MB/s camera raw over Tailscale. Simplest verifiable
relief first (no protocol change):

### 3.A Not-yet-verified CODED items on the rebuild list (current server)
- Camera-frame holder fix already in source (`handleLiveWs` `finally`
  calls `stopPreviewStream()` + `micController.stopLiveStream()`); the
  previously-installed APK predates it. Rebuild+`install -r` REQUIRED to
  deliver camera-release + flip correctness to the device.
- Front cam (square 1088) mis-size: source routes `camLens` correctly;
  the old APK hard-coded the PiP. Same rebuild fixes it.

### 3.B Do next (heat simplest-first)
- Screen: cap capture width (e.g. 720) + JPEG Q60 before encode.
- Camera: chip rotation re-encode when rotation==0; lower Q55→46.
- WS: cap combined fps lower (e.g. `minFrameGap=60→90`); skip re-encode
  when no new seq.
- Optionally: NVENC host render via ffmpeg.
- Not done yet (needs live M51): A/B of FPS×Q for a safe thermal envelope,
  then rebuild + smoke-verify, update docs, commit/push.

## 4. Delivery record

- v2.4.x run shipped: reb rand fix (front cam), audio swap (pre-roll +
  server post-processing), grid fix, volume slider — all pushed live.
- v2.3.3 remote-admin + triple RECORD + PiP PLAY (built/shipped earlier).
- Docs: AGENTS.md durable reference; this handoff is fresh for the
  WiFi/BT + volume phase. Next agent reads §1 (code to echo) → §0 (new
  phase) → §2/§3 (heat, optional) → then builds.

## 5. Hard rules (unchanged)

- FROZEN sector (AGENTS.md §3.1): `AuthManager.kt`, `TlsManager.kt`,
  security enforcement in `SimpleHttpServer.kt`, auth paths. ADD endpoints;
  never modify auth logic.
- Helpers run FGS-only; no Activity refs; no `androidx.compose`.
- Phone `HttpURLConnection` REQUIRES `\r\n` line endings.
- WS server frames > 64 KiB MUST use the 127-bit extended length.
- CameraX `bindToLifecycle` = main thread + STARTED lifecycle.
- Artemis never default SMS/dialer (user requirement).
- Dashboard JS is browser-cached aggressively — verify edits with
  cache-busted URLs (`?x=N`).
- M51 Tailscale adb serial DRIFTS — `adb devices -l` each session.