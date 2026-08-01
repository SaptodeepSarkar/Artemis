# Artemis Sentinel — Handoff: Video-Call / LIVE VIEW (v2.3.0)

> Written 2026-08-01, after v2.3.0 shipped (helpers + video-call phase,
> live-verified). Read `docs/AGENTS.md` first (project reference +
> frozen-sector rules), then `docs/SECURITY.md` (threat model), then this
> document, then the source.

---

## 1. DONE — what shipped in v2.3.0 (all live-verified on the M51)

### 1.1 Zero-consent screen capture (accessibility pivot)

User directive: "the app is a device admin so it should not require
consent for anything."

- `feature/RemoteControlService.kt` — the EXISTING accessibility service
  (remote-control input) now also captures: `takeScreenshot()` (API 30+,
  `canTakeScreenshot="true"` in `res/xml/accessibility_service_config.xml`).
  ONE service = remote control + screen capture (one Settings toggle).
- `feature/ScreenCaptureController.kt` — accessibility-first `captureFrame()`;
  MediaProjection (`service/ScreenCaptureService.kt`) kept only as
  documented fallback. `/screen/status` returns `enabled/active/method`.
- VERIFIED: `POST /screen/capture` → valid JPEG with ZERO dialogs, app
  swiped away, and after `install -r`. Enable (one-time):
  `settings put secure enabled_accessibility_services
  com.example.artemis/com.example.artemis.feature.RemoteControlService;
  settings put secure accessibility_enabled 1` — survives reinstall/reboot.

### 1.2 Live streams (the "video call" area)

- `GET /api/v1/stream/screen` — MJPEG (`multipart/x-mixed-replace`),
  ~2.5 fps, frame ≈ 160 KB (accessibility backend).
- `GET /api/v1/stream/camera?camera=front|back` — MJPEG, ~0.5–0.7 fps
  (CameraX single-capture latency; known limit, preview-stream feed is
  the future optimization).
- `GET /api/v1/stream/mic` — raw PCM16 mono 44.1 kHz (Microphone +
  AudioRecord loop → synchronized buffer; recording and streaming are
  mutually exclusive).
- Server plumbing: `HttpResponse.streamBody` (suspend lambda) +
  `sendStreamHead()`; stream ends on client disconnect (socket close).

### 1.3 Dashboard LIVE VIEW panel (replaces the capture button)

- `dashboard_web/templates/dashboard.html` + `static/js/dashboard.js`:
  main feed img (screen ⇄ back-cam toggle), front-cam PiP top-right
  (toggle), mic audio via WebAudio ScriptProcessor (toggle), STOP button,
  `beforeunload` kills all streams. Battery card in the status strip.
- Web proxies: `/api/device/{host}/{port}/stream/{screen,camera,mic}`,
  `.../battery`, `.../screen/status`, `.../sms/delete`,
  `.../logs/calls/delete` (`server/device_client.py` `stream()` =
  socket-stream generator → FastAPI `StreamingResponse`).
- VERIFIED in-browser: screen feed 1080×2400, PiP 960×720, audio queue
  filling, all toggles, clean stop.

### 1.4 Deletes + battery

- `POST /api/v1/sms/delete` + `/logs/calls/delete` by provider row id
  (`SmsProvider.deleteSms`, `CallLogsProvider.deleteCallLog`,
  permission-gated). Manifest: `WRITE_SMS`, `WRITE_CALL_LOG`.
- Call-log delete WORKS on M51 (`pm grant` WRITE_CALL_LOG succeeds on
  Samsung). SMS delete is silently no-op'd unless Artemis is the default
  SMS app (Android restriction) — API returns a clear message; phone
  dashboard has SET DEFAULT SMS / SET DEFAULT DIALER buttons.
- `feature/BatteryHelper.kt` → `GET /api/v1/battery` (levelPercent,
  isCharging, chargeSource, status, health, temperatureC, voltageMv,
  technology). NOTE: data class is `BatterySnapshot` (BatteryInfo name
  collides with `DeviceInfoProvider`'s).

### 1.5 Other helpers shipped (previous handoff items)

- `CameraFeedController` (`/camera/feed/start|stop|latest`),
  `FileSystemHelper` (`/files/list|download|upload`, app-scoped root,
  traversal 403, external roots gated by MANAGE_EXTERNAL_STORAGE),
  `ContactsProvider` (`/contacts`), location `?fresh=1`.
- Version: code 6 / `2.3.0`; `startForeground` mask = exactly
  `mediaProjection` on API 34+; `LifecycleService` so CameraX
  `bindToLifecycle` runs on the FGS lifecycle (main thread).

## 2. NEXT phase — candidates (pick what fits)

1. **Front-cam feed smoothness**: camera stream is 0.5–0.7 fps because
   each frame is a fresh CameraX ImageCapture (~1.5 s on M51). Build a
   repeating front-cam feed (mirror `CameraFeedController` or make it
   lens-aware) or switch the stream handler to the feed's latest-frame
   cache. Also consider downscaling frames before the JPEG encode.
2. **SMS delete UX**: set-as-default flow works, but the web dashboard
   could surface the default-SMS status (check
   `Telephony.Sms.getDefaultSmsPackage` in a `/sms/status`-style
   endpoint) instead of the generic failure message.
3. **Stream auth hardening** (if the threat model demands it): streams
   are behind `requireAuth` like everything else, but they are
   long-lived connections — verify token rotation mid-stream behaves
   (it does not kick streams today; document or enforce).
4. **Screen recording** (file output) via the same accessibility
   backend — today it's capture-only (screenshots + stream); a
   recording loop would reuse the accessibility `takeScreenshot` path
   into a MediaMuxer/MP4 writer.
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

## 4. Delivery record

- Commit + push `master`, message `v2.3.0:...`, tag `v2.3.0`, clean tree.
- `docs/AGENTS.md` §2.2/§2.6 updated; `docs/SECURITY.md` header bumped.
