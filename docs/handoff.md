# Artemis Sentinel — Handoff: v2.3.3 remote-admin control (built, deliver it) → next phase

> Written 2026-08-02, after v2.3.2 shipped and the v2.3.3 remote-admin
> control phase was **built in the working tree but not yet committed**.
> Read `docs/AGENTS.md` first (project reference + frozen-sector rules), then
> `docs/SECURITY.md` (threat model), then this document, then the source.
> Note: PRD.md / PROJECT.md / SYSTEM.md / TASKS.md / MEMORY.md and the old
> design/research docs were deleted as stale (they described a Ktor/SQLCipher/
> JWT/WebRTC plan this codebase never used). Docs = AGENTS.md + SECURITY.md +
> this file.

---

## 1. DONE and SHIPPED — v2.3.2 (dashboard UX, live-verified)

- LIVE VIEW camera-only (`liveSource` fixed `"cam"`); SCREEN toggle, status
  badge, front-cam PiP removed; FLIP always works.
- Location auto-fetches on dashboard open and on REFRESH.
- SMS delete button removed (Android-blocked; READ-ONLY note shown); call-log
  delete retained.
- Committed `406585c`, tag `v2.3.2`. Phone then at v2.3.1 / versionCode 7.

## 2. BUILT — v2.3.3 remote-admin control (in working tree, DELIVER IT)

The remote-administrator phase the user asked for is fully implemented in
the source (versionName/versionCode **2.3.3 / 8**) and validated by build +
basic smoke checks. It is **not yet committed or pushed** — the immediate
job is to deliver it. Everything below is already in the code.

### 2.1 Screen / input control (HTTP + WS, accessibility, zero new consent)

- `feature/RemoteInputController.kt` — plain-Kotlin facade over
  `RemoteControlService.instance` (accessibility): `tap`, `longPress`,
  `swipe(x1,y1,x2,y2,ms)`, `global(action: home|back|recents|lock|
  notifications|quick_settings|power)`. Returns true = queued; false when
  the service isn't connected (server answers `409 accessibility_disabled`).
- HTTP (ADD routes in `SimpleHttpServer`, frozen sector untouched, all
  `requireAuth`-wrapped): `POST /api/v1/control/tap|longpress|swipe|action`.
- WS: rides the LIVE VIEW socket as `{"cmd":"input","action":...}`.
- Dashboard: control pad (HOME / BACK / RECENTS / LOCK) + tap-on-canvas +
  drag-to-swipe. Canvas coords map to device px on the 1080×2400 feed
  (`x_dev = x_canvas * feed_w / canvas_w`, letterboxing accounted for).

### 2.2 Triple RECORD — screen + front + rear, one button

- `feature/TripleRecorder.kt`: three `MediaCodec` H.264 sessions, writes
  `<filesDir>/data/record/{screen,front,rear}/rec_<epochMs>.mp4`.
- Screen: same accessibility `RemoteControlService.capture()` loop
  (`takeScreenshot`), downscaled ≤960 px, drawn to encoder input surface,
  ~2–3 fps. MediaProjection (`ScreenCaptureService`) stays documented fallback.
- Front + rear: `CameraController.recordSink` (live-preview analyzer NV21
  frames) + `startSecondPreviewStream` for the off-lens. The M51 supports
  concurrent cameras (both lenses record); if a device rejects the second
  lens, the recorder degrades gracefully — the rejected folder stays empty
  (handoff rule). Camera
  sessions take byte buffers (NV21→NV12/I420), screen takes a surface.
- Endpoints (all `requireAuth`): `POST /api/v1/record/start`
  (`{"lens":"front|back"}` optional), `/stop` →
  `{"status":"saved","paths":{screen:[..],front:[..],rear:[..]}}`,
  `/status`, `/list`, `GET /api/v1/record/{media}/{id}/file` (binary MP4).
- WS alt: `{"cmd":"record","v":"on|off"}`; phone pushes
  `{"event":"record_saved"}`/`record_status` text frames back.
- `onPreviewLensChanged` re-tracks the second bind on FLIP mid-recording.

### 2.3 PiP PLAY

- Dashboard `recordingsSection` lists the three folders from
  `/record/list`; PLAY per file streams `/api/device/{h}/{p}/record/…/file`
  into the top-right `recordPipVideo` (`<video>` overlay, close button).
- Phone-side Android PiP (PictureInPictureMode) is a possible later
  alternative; dashboard PiP is the delivered path.

### 2.4 Delivery checklist (do it now)

- **Commit + tag + push v2.3.3** (commit convention:
  `v2.3.3:remote-admin control — accessibility input (tap/swipe/long-press/
  global) over HTTP+WS, triple RECORD screen+front+rear → data/record/…,
  PiP PLAY`, tag `v2.3.3`). Work tree is currently dirty with these
  changes; get it clean.
- Smoke-verify against the M51 if reachable: each folder produces a
  playable MP4 (`ffprobe` after `adb pull`), WS survives record start/stop
  mid-stream (no reconnect; frames stay in sync), tap lands on the right
  app, HOME/BACK/LOCK work with the app never opened.
- Update `docs/AGENTS.md` §6 if any version history line shifts.

## 3. NEXT phase (proposed) — after v2.3.3 is pushed

The user's roadmap is largely satisfied by v2.3.3. Candidate next items
(confirm priority with the user before starting):

- **Recording browser/dashboard polish**: duration badge per recording,
  delete recordings, grouped REPLAY in the media catalogue.
- **FGS 6-hour hardening (future API 35)**: the app already targets
  API 29/37, so the Android-16 6-hour FGS timeout is NOT yet pressing — the
  M51 is Android 12/API 31. WorkManager keep-alive is only worth building
  when it actually bites. Do not over-engineer.
- **mTLS via QR-paired client certs** (SECURITY.md §2.6) — requires a UX
  change the user explicitly deferred; only if requested.
- Anything else the user brings next; handoff should be rewritten fresh each
  phase.

Pick ONE. Never build all of them speculatively.

## 4. Hard rules (unchanged)

- FROZEN sector (`docs/AGENTS.md` §3.1): `AuthManager.kt`, `TlsManager.kt`,
  security enforcement in `SimpleHttpServer.kt`, auth paths in
  `device_client.py` / `artemis.py`. ADD endpoints; never modify auth logic.
- Helpers run FGS-only: endpoints answer with the app swiped away / never
  opened after reboot.
- No Activity references in helpers; no `androidx.compose` in helper/server
  layer.
- `HttpURLConnection` on the phone REQUIRES `\r\n` line endings.
- Artemis must NEVER become default SMS app or default dialer (user
  requirement — stock Samsung apps keep those roles).
- WebSocket server frames with payloads > 64 KiB MUST use the 127 (64-bit)
  extended length — see AGENTS.md §2.7.
- CameraX `bindToLifecycle` requires main thread + STARTED lifecycle
  (`LifecycleService`); preview bindings are persistent — photo/frame
  captures stop the preview first (camera exclusivity).
- Dashboard JS is aggressively browser-cached: verify edits with
  cache-busted URLs (`?x=N`).

## 5. Delivery record

- v2.3.2 shipped: commit `406585c`, tag `v2.3.2`, pushed, clean tree.
- v2.3.3: built in working tree (files `RemoteInputController.kt`,
  `TripleRecorder.kt`, edits to `SimpleHttpServer.kt`, `CameraController.kt`,
  `RemoteControlService.kt`, `AndroidManifest.xml`, dashboard.js/html).
  **Status: UNCOMMITTED — this handoff's first action is to commit + push
  `v2.3.3`.**
- Docs: deleted stale planning docs; AGENTS.md + SECURITY.md updated to
  v2.3.3; this handoff rewritten (2026-08-02).