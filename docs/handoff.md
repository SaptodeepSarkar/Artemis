# Artemis Sentinel — Handoff: Remote-admin control (screen control + 3-way RECORD + PiP PLAY)

> Written 2026-08-01, after v2.3.2 shipped (dashboard UX polish) and
> updated with the NEXT phase brief per user request. Read
> `docs/AGENTS.md` first (project reference + frozen-sector rules), then
> `docs/SECURITY.md` (threat model), then this document, then the source.

---

## 1. DONE — v2.3.2 (dashboard UX, verified in-browser)

- LIVE VIEW is camera-only (`liveSource` fixed `"cam"`); SCREEN toggle,
  status badge and front-cam PiP removed; FLIP always works.
- Location auto-fetches on dashboard open and on REFRESH.
- SMS delete button removed (Android-blocked; READ-ONLY note shown);
  call-log delete retained.
- Phone unchanged (v2.3.1 / versionCode 7). Committed `406585c`, tag
  `v2.3.2`.

## 2. NEXT phase — REMOTE-ADMIN CONTROL (build steps)

User request: remote-administrator control of the phone — control the
screen, a RECORD button that starts recording the screen AND both the
front and rear cameras when the live preview is started (and stops it),
recordings stored under a `data` folder with a `record` folder containing
three media folders, and a PLAY button for direct PiP playback.

### 2.1 Screen control (tap / swipe / system buttons)

Already exists phone-side, NOT yet wired to the network:
`RemoteControlService` (accessibility) has `dispatchGesture` (tap path
at `RemoteControlService.kt:166-169`, `GestureDescription.Builder` +
`StrokeDescription`) and `performGlobalAction` (HOME, BACK, RECENTS,
NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG, LOCK_SCREEN — lines
99-152). The M51's one-time accessibility enable already persists, so
this is ZERO new consent.

Steps:
1. Add a `RemoteInputController` helper (house style: plain Kotlin
   class taking `Context`, no HTTP knowledge) exposing
   `tap(x, y)`, `swipe(x1,y1,x2,y2,ms)`, `longPress(x,y)`,
   `global(action)` — all delegating to `RemoteControlService.instance`
   (null → `{"error":"accessibility_disabled"}`).
2. Wire into `SimpleHttpServer.kt` (frozen sector untouched — ADD
   routes): `POST /api/v1/control/tap` `{"x":..,"y":..}`,
   `POST /api/v1/control/swipe`, `POST /api/v1/control/action`
   `{"action":"home|back|recents|lock|notifications|quick_settings|power"}`,
   all `requireAuth`-wrapped. Swipes need a
   `GestureDescription.StrokeDescription(path, 0, durationMs)` with a
   multi-point `Path` (dispatchGesture supports it — mirror the tap
   helper).
3. Dashboard: small control pad in the LIVE VIEW panel — tap = click on
   the canvas (map canvas coords to device px: canvas shows the scaled
   1080×2400 feed, so `x_dev = x_canvas * (feed_w / canvas_w)`), swipe =
   drag on the canvas, plus HOME / BACK / RECENTS / LOCK buttons.
   Send over the EXISTING WS (`sendWs({cmd:"input",...})` — add an
   `"input"` command to the phone's `wsLiveSessionHandler` reader;
   single-pixel feed taps are fine, no new stream needed).

### 2.2 RECORD — screen + front cam + rear cam, one button

Trigger: RECORD button appears when LIVE VIEW is streaming; tap starts
all three recorders, tap again (STOP) stops and saves.

Storage (user's exact layout, under the app data dir — `filesDir` is
already whitelisted in `FileSystemHelper` roots so `/files/list` can
browse it):
```
<app-data>/data/record/screen/rec_<epochMs>.mp4
<app-data>/data/record/front/rec_<epochMs>.mp4
<app-data>/data/record/rear/rec_<epochMs>.mp4
```

Steps:
1. New `TripleRecorder` helper (`feature/`, FGS-only, no Activity):
   three `MediaCodec` H.264 encoders + one `MediaMuxer` each,
   feeding from:
   - **screen**: the accessibility `takeScreenshot` loop ALREADY used
     by `RemoteControlService.capture()` — capture→encode→mux at the
     preview cadence (~2-3 fps at 1080×2400; raise fps by capping
     width ~960 for recording). MediaProjection
     (`ScreenCaptureService`) stays the documented fallback.
   - **front + rear**: CameraX supports simultaneous two-camera binding
     on API 30+ (M51 is API 31): bind BOTH `ImageAnalysis` use cases
     (640×480, the persistent-binding pattern from
     `CameraController.startPreviewStream`) → YUV→encoder→mux. If the
     device rejects concurrent cameras, fall back to recording the
     currently-flipped lens and mark the other folder empty.
   - Stop: `stop()` on all three, close encoders/muxers, emit
     `{"status":"saved","paths":[...]}`.
2. IMPORTANT — live-preview exclusivity: the WS cam preview holds the
   cameras. Recording must REUSE the same `ImageAnalysis` frames
   (add a frame-copy hook in `CameraController` that the recorder
   subscribes to) rather than rebinding — rebinding would kill the
   live stream. Same for screen: reuse the capture loop, don't fight
   the WS writer.
3. Endpoints (ADD, frozen sector untouched): `POST /api/v1/record/start`
   (returns immediately, recorders run on worker coroutines),
   `POST /api/v1/record/stop`, `GET /api/v1/record/status`,
   `GET /api/v1/record/list` → `{"screen":[...],"front":[...],"rear":[...]}`
   (filenames + sizes + timestamps from the three folders),
   `GET /api/v1/record/{media}/{id}/file` (binary, mirror
   `videoFileHandler`). WS alternative: `{"cmd":"record","v":"on|off"}`
   in `wsLiveSessionHandler` so the dashboard RECORD button rides the
   existing socket.
4. Dashboard: RECORD button (red dot, `toggleRecord()`), STOP swaps
   label; wire to the WS command (or HTTP POST). Reuse the binary
   frame-parsing already in `dashboard.js` — no new rendering path.

### 2.3 PLAY — direct PiP playback

Steps:
1. Dashboard RECORDINGS row (in the LIVE VIEW panel or CAPTURED MEDIA):
   one entry per file from `/record/list`, grouped by the three media
   folders.
2. PLAY button per entry → PiP player: reuse the OLD PiP box slot
   (top-right overlay, `livePipWrap` position) with a `<video>` element
   streaming `GET /api/device/{host}/{port}/record/{media}/{id}/file`
   via the existing authenticated proxy (browser `<video>` plays MP4;
   the phone route is the same binary response as `video/{id}/file`).
   Close button + `pip`-style draggable, or fixed top-right like before.
3. Android-side PiP (PictureInPictureMode) is an alternative if a
   phone-local player is wanted later — dashboard-side PiP is the
   direct path and matches the previous UX.

### 2.4 Acceptance checklist

- FGS-only: RECORD starts/stops with the app swiped away (the FGS and
  accessibility service keep running — no Activity).
- All three MP4s appear under
  `<app-data>/data/record/{screen,front,rear}/` and play back (VLC/ffprobe
  on the desktop after pull, or the PiP player).
- WS survives RECORD start/stop mid-stream (no reconnect, no drop —
  same rule as the 64 KiB framing fix: don't break the live frames
  while muxing).
- Screen control: tap lands on the right app, HOME/BACK/LOCK work from
  the dashboard with the app never opened.
- `docs/AGENTS.md` gets a §2.9 + endpoint-table rows; `handoff.md`
  rewritten; commit `v2.3.3:...`, tag `v2.3.3`, push.

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
  (64-bit) extended length — see AGENTS.md §2.7 for why.
- CameraX `bindToLifecycle` requires the main thread + STARTED lifecycle
  (`LifecycleService`); preview bindings are persistent — photo/frame
  captures stop the preview first (camera exclusivity).
- Dashboard JS is aggressively browser-cached: verify edits with
  cache-busted URLs (`?x=N`); static-file edits need no server restart.

## 4. Delivery record

- v2.3.2 shipped: commit `406585c`, tag `v2.3.2`, pushed, clean tree.
- This handoff (remote-admin control brief) committed immediately after
  writing, per user instruction.
