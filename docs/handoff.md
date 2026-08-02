# Artemis Sentinel — Handoff: v2.4.x LIVE VIEW shipped → next phase: heat / offload optimization

> Written 2026-08-02. Read `docs/AGENTS.md` first (project reference),
> then `docs/SECURITY.md`, then this handoff. Phases: this file is the
> brief for the NEXT agent. Docs = AGENTS.md + SECURITY.md + this file.

---

## 1. SHIPPED — LIVE VIEW redesign + layout fixes (v2.4.0 → v2.4.1)

Commits on GitHub, tree clean:
- `54870cd` v2.4.0 — LIVE VIEW side-by-side redesign: phone screen LEFT,
  camera feed RIGHT, icon-only 2-column control grid below the camera,
  mobile nav (HOME/BACK/RECENTS/LOCK) at the top of the grid,
  SCREEN/CAM source toggle removed, all buttons icon-only.
- `ce4414d` v2.4.1 — feed-driven sizing: screen card width tracks the real
  screen feed (no side black bars), camera panel sized to the live feed
  aspect (front 1088×1088 / rear 480×640, no top/bottom bars), camera +
  control column hugged to the screen right edge (`items-start`, fixed
  220px, no stretch), `fitLivePane()` recomputes geometry off
  `createImageBitmap` dims on every live frame.

## 2. LIVE VIEW pipeline — current phone-side HEAT sources (the next phase)

`ws/live` (phone = WS server) streams three channels on one socket:
`0x01` screen JPEG, `0x02`/`0x03` camera (rear/front) JPEG, `0x04` audio.

Phone does ALL image encode on CPU — this is the overheating:
- **Screen** [feature/RemoteControlService.kt `capture()`]: full-res
  `takeScreenshot` → `Bitmap.wrapHardwareBuffer` → `compress(JPEG, 80)`
  every loop.
- **Camera** [feature/CameraController.kt `startPreviewStream` +
  `nv21ToJpeg`]: CameraX ImageAnalysis 640×480 → `imageToNv21` (plane
  walk) + `YuvImage.compressToJpeg(..., 55)` EVERY frame, plus an
  NV21→Bitmap→rotate→re-encode when rotation≠0 (the M51 front is square;
  rear 4:3).
- **WS loop** [SimpleHttpServer.kt `handleLiveWs`] interleaves both at up
  to ~16 fps (`minFrameGap=60`).
- Host: NVIDIA RTX 3050 (NVENC capable) present; NO ffmpeg, NO opencv
  installed yet.

Goal (user): phone sends raw captures; the server's GPU does ALL heavy
lifting; phone stops overheating.

## 3. NEXT phase — heat / offload (pick ONE, confirmed priority)

The full "raw NV21 + raw RGBA to server GPU (NVENC)" goal is large and at
its heaviest pushes ~7MB/s of camera raw over Tailscale. Immediate,
verifiable heat relief (no protocol change) is the safest first commit:

### 3.A Not-yet-verified CODED items on the rebuild list (current server)
- Camera-frame holder fix is already in source (`handleLiveWs` `finally`
  calls `stopPreviewStream()` + `micController.stopLiveStream()`); the
  installed APK predates it. Rebuild+`install -r` is REQUIRED to deliver
  camera-release + flip correctness to the device.
- Front cam (square 1088) mis-size: source routes `camLens` correctly;
  the old APK hard-coded the PiP. Same rebuild fixes it.

### 3.B Do next (heat simplest-first)
- Screen: cap capture width (e.g. 720) + JPEG Q60 before encode.
- Camera: chip the rotation re-encode when rotation==0 (skip
  NV21→Bitmap→rotate→re-encode); lower camera Q55→46.
- WS: cap combined fps lower (e.g. minFrameGap=60→90) and only
  re-encode when a NEW seq arrives (already gated by seq for camera;
  screen could skip if same timestamp).
- Optionally: NVENC on host via ffmpeg for the browser re-render.

Not done yet (needs live M51): A/B of FPS×Q to find the safe thermal
envelope, then rebuild + smoke-verify, update docs, commit/push.

## 4. Delivery record

- v2.3.3 (remote-admin control + triple RECORD + PiP PLAY): built and
  shipped in the dashboards fixes run; source had the post-v2.3.2 fixes.
- v2.4.0 `54870cd` — LIVE VIEW side-by-side redesign + icon-only controls
  + source-toggle removal. Pushed.
- v2.4.1 `ce4414d` — feed-driven sizing + layout hug fixes. Pushed. Tree
  clean.
- Docs: AGENTS.md is durable reference; this handoff rewritten fresh for
  the heat/offload phase. Next agent reads §1→§2→§3.B and rebuilds.

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