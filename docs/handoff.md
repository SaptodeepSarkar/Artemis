# Artemis Sentinel — Handoff: LIVE VIEW WebSocket pipeline (v2.3.1)

> Written 2026-08-01, after v2.3.1 shipped (LIVE VIEW WebSocket pipeline,
> flip, no-default-app, live-verified). Read `docs/AGENTS.md` first
> (project reference + frozen-sector rules), then `docs/SECURITY.md`
> (threat model), then this document, then the source.

---

## 1. DONE — what shipped in v2.3.1 (all live-verified on the M51)

### 1.1 WebSocket LIVE VIEW replaces MJPEG (the user's static + lag fixes)

User feedback that drove this phase: "camera feed is green/grey static",
"too laggy — I want smooth playback", "flip feature", and "I don't want
the app to be a default caller/SMS — I want to keep receiving calls and
SMS on my phone's own caller and SMS apps."

- **Root cause found and fixed**: the browser-side static AND the WS
  drops were ONE bug — `LiveWsProtocol.encodeFrame` only implemented the
  16-bit extended length (opcode 126). Full-res screen JPEGs (200–400 KB)
  silently wrapped the length field, desyncing the client
  (websockets lib → 1002 "invalid opcode"; browser canvas → dropped
  stream). **64-bit extended length (opcode 127) added for payloads
  > 64 KiB.** Cam frames stayed under 64 KiB, which is why only screen
  mode broke. Verified by a raw-socket frame validator
  (`/tmp/ws_hex.py` pattern) and the full browser flow.
- **`GET /api/v1/ws/live`** — RFC 6455 WebSocket, hand-rolled in
  `server/LiveWebSocket.kt` (NO new Android deps): `acceptKey` =
  Base64(SHA1(key+MAGIC)), unmasked server→client, masked client→server,
  ping/pong/close, text controls + binary media.
- **Frame protocol** (binary): `[1B channel][4B BE length][data]`.
  `1`=screen JPEG, `2`=back-cam, `3`=front-cam (PiP), `4`=PCM16 mic
  (mono 44.1 kHz, ~4 KB chunks). JSON text controls:
  `{"cmd":"source","v":"screen|cam"}`, `{"cmd":"camera","v":"back|front"}`,
  `{"cmd":"pip","v":"on|off"}`, `{"cmd":"audio","v":"on|off"}`.
- **Performance**: back cam ≈7.5–8 fps (was 0.5 fps MJPEG) via the new
  persistent 640×480 `ImageAnalysis` preview binding in
  `CameraController.kt` (`previewExecutor`, `startPreviewStream`/
  `stopPreviewStream`; photo/frame captures stop the preview first —
  camera exclusivity). Screen ≈3 fps (accessibility `takeScreenshot`
  latency — inherent). Audio continuous.
- **Flip**: `LiveState.camLens` (BACK default) + `{cmd:"camera"}` —
  dashboard FLIP button toggles live, label syncs.
- **Dashboard**: `server/__init__.py` `@app.websocket` proxy →
  `websockets.connect` (cert-pin trust model, token on upgrade);
  `static/js/dashboard.js` binary-channel parsing → `<canvas>`
  (`liveMain`/`livePip`) + WebAudio; state in
  `liveOn/liveSource/liveLens/micAudioOn`; `sendWs({cmd})`; onopen sends
  source/camera/pip/audio and shows the PiP wrap; auto-starts mic audio.
- VERIFIED end-to-end in the browser: screen phase (WS open, main
  1080×2400 canvas lit, PiP 1088×1088 lit + visible), cam switch (WS
  HELD — previously dropped), flip (lens→front, label "FLIP: FRONT"),
  STOP (clean teardown, ws null). Protocol level: `/tmp/ws_repro.py`
  (screen→cam→flip, all channels, still-open), `/tmp/ws_hex.py` (every
  frame's opcode/length/channel validated OK incl. 127-extended).

### 1.2 No default-app dependency (user mandate)

- **SET DEFAULT SMS / SET DEFAULT DIALER card REMOVED** from
  `DashboardScreen.kt`. Artemis never becomes the default SMS app or
  default dialer — the user's stock Samsung apps keep receiving calls
  and SMS. SMS-delete remains Android-blocked (provider protection;
  `WRITE_SMS` not pm-grantable on this device) — the dashboard copy
  states the caveat; call-log delete still works (`WRITE_CALL_LOG`
  grantable on Samsung).

### 1.3 Version

- versionCode 7 / versionName "2.3.1"; `Server: Artemis/2.3.1`.
  Build: `export JAVA_HOME=/opt/android-studio/jbr && export
  PATH=$JAVA_HOME/bin:$PATH && ./gradlew :app:assembleDebug -q`.
  Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  then `am startservice -n com.example.artemis/.service.ArtemisSentinelService`
  (install force-stops the app; `am start` alone does NOT bring the FGS
  up on this device).

## 2. NEXT phase — candidates (pick what fits)

1. **Screen fps**: accessibility `takeScreenshot` caps screen mode at
   ~3 fps (each call is a full display capture + JPEG encode on the
   executor). A lower-res target (cap `MAX_WIDTH` smaller for the WS
   path, e.g. 960 px) and/or JPEG quality 60 would raise fps at the cost
   of sharpness — the dashboard could offer a quality toggle.
2. **Front-cam fps in screen mode**: the PiP runs the same 640×480
   preview; bump to 720p when the M51 allows, or drop the PiP to a lower
   target resolution to save encode time.
3. **Backpressure / token rotation on long-lived WS**: the WS session
   lives as long as the browser keeps it; a rotating access token does
   not kick an open session (documented today, not enforced — decide
   whether that matters for the threat model).
4. **Reconnect UX**: `liveWs.onclose` sets `liveOn=false` — the button
   shows START again; an auto-reconnect (with backoff) would smooth
   transient Tailscale hops.
5. Anything from the original helper backlog not yet wired into the
   dashboard UI (files browser panel, contacts panel).

## 3. Hard rules (unchanged)

- FROZEN sector (`docs/AGENTS.md` §3.1): `AuthManager.kt`, `TlsManager.kt`,
  security enforcement in `SimpleHttpServer.kt`, auth paths in
  `device_client.py` / `artemis.py`. ADD endpoints; never modify auth logic.
- Helpers run FGS-only: endpoints must answer with the app swiped away /
  never opened after reboot (acceptance test).
- No Activity references in helpers; no `androidx.compose` in
  helper/server layer.
- `HttpURLConnection` on the phone REQUIRES `\r\n` line endings.
- Artemis must NEVER become default SMS app or default dialer (user
  requirement — stock Samsung apps keep those roles).
- WebSocket server frames with payloads > 64 KiB MUST use the 127
  (64-bit) extended length — see the 2.7 doc note in AGENTS.md for why.

## 4. Delivery record

- Commit + push `master`, message `v2.3.1:...`, tag `v2.3.1`, clean tree.
- `docs/AGENTS.md` §2.2/§2.7 updated; `docs/SECURITY.md` header bumped.
