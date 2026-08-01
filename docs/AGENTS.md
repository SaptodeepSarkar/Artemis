# Artemis Sentinel — AGENTS.md

> Current: **v2.3.1** (2026-08-01). Read this first, then `docs/SECURITY.md`
> (threat model), then `docs/handoff.md` (the current phase brief for the
> next agent). This file is the durable project reference; the phase brief
> lives in `handoff.md`.

---

## 1. What this project is

Artemis Sentinel is a self-hosted Android monitoring / remote-control
system ("RAT" in the project's own terms) with three parts:

| Part | Where | Role |
|---|---|---|
| **Phone app** | `Artiest/` (Gradle, Kotlin, package `com.example.artemis`) | Raw `ServerSocket` + TLS HTTPS server on **:8443**; foreground service keeps it alive 24/7; pairing via 6-digit code |
| **Web dashboard** | `dashboard_web/` (FastAPI/Starlette, `python3 main.py`, port 5000) | Fleet view, per-device controls, media catalogue, encrypted SQLite store |
| **CLI** | `dashboard/artemis.py` | Pairs, queries, downloads from the terminal; shares the dashboard DB |

The phone server is the heart: every capability (location, camera, mic,
call logs, SMS, video, admin lock) is an authenticated HTTP endpoint on
`:8443` behind the TLS + TOFU-pin client stack.

## 2. Current state (v2.3.1 — shipped, live-verified)

### 2.1 The security backbone (DONE, FROZEN — do not touch)

- TLS 1.3 default on the phone server; restricted 1.2 fallback
  (ECDHE+AEAD) for old Android. Phone TLS key = **BouncyCastle software
  RSA-2048 PKCS12** — AndroidKeyStore RSA fails every Conscrypt handshake
  on Samsung (error 04000044). AndroidKeyStore is used only for the
  pairing HMAC.
- TOFU pinning: SHA-256 of cert DER, constant-time compare; mismatch →
  reject + delete full trust → re-pair required. Dashboard and CLI both pin.
- Token lifecycle: access 1h, rotating refresh 30d, 60s grace, replay →
  revoke + purge. Expiry never unregisters.
- Pairing: 6-digit code, rotates 5 min + per pair + on app restart; never
  logged or networked. Pairing survives `install -r` (keystore persists).
- Lockout: 5 fails/IP → 429, cap 5 min; 120 req/min/IP (loopback exempt).
- Dashboard store: encrypted SQLite at `~/.config/artemis/artemis.db`
  (Fernet `dashboard_store.key`, `enc:` prefix on secrets). Legacy JSON
  migrated once then deleted. `.config` holds only db + key + admin password.

### 2.2 Features (all live on the phone)

| Endpoint group | Status |
|---|---|
| `/api/v1/health`, `/health` | ✓ version, uptime |
| `/api/v1/device/info` (+ battery/network/storage) | ✓ |
| `/api/v1/location/current`, `/history` | ✓ |
| `/api/v1/camera/*` (list, capture, captures, file) | ✓ |
| `/api/v1/mic/record/*` (start/stop/recordings/file) | ✓ |
| `/api/v1/mic/stream` (live PCM16 44.1kHz for the video-call audio) | ✓ |
| `/api/v1/logs/calls`, `/api/v1/sms` (+ `POST .../delete` by row id) | ✓ |
| `/api/v1/callrecorder/*`, `/api/v1/callrecordings/*` | ✓ |
| `/api/v1/video/record`, `/list`, `/{id}`, `/{id}/file` | ✓ |
| `/api/v1/admin/lock` (device-admin lockNow) | ✓ |
| `/api/v1/battery` (BatteryHelper — dashboard header) | ✓ |
| `/api/v1/screen/status`, `/screen/capture` (accessibility-first) | ✓ |
| `/api/v1/stream/screen`, `/stream/camera`, `/stream/mic` (MJPEG/PCM live) | ✓ legacy — LIVE VIEW now uses WS (2.7) |
| `/api/v1/files/list`, `/download`, `/upload` (app-scoped root) | ✓ |
| `/api/v1/contacts` (READ_CONTACTS) | ✓ |
| `/api/v1/camera/feed/start`, `/stop`, `/latest` | ✓ |
| Screen recording / screenshots | ✓ accessibility `takeScreenshot` (API 30+), ZERO consent dialogs; MediaProjection kept only as documented fallback |
| `/api/v1/ws/live` (RFC 6455 WebSocket, LIVE VIEW) | ✓ v2.3.1 — see 2.7 |

### 2.3 24/7 persistence (v2.2.0 — live-verified)

- **Battery-optimization exemption**: in-app prompt +
  `dumpsys deviceidle whitelist +com.example.artemis`; entry survives
  `install -r`.
- **Indefinite wake lock** while the server runs (the old 4h cap let Doze
  take over).
- **Doze-exit re-arm**: `DozeRecoveryReceiver` registered **dynamically
  from the running FGS** (manifest receivers for USER_PRESENT/SCREEN_ON
  cannot execute on Android 12+ for backgrounded apps). On
  SCREEN_ON / USER_PRESENT / power / connectivity events it TCP-probes
  `127.0.0.1:8443` and restarts the service if the socket is dead.
  Startup grace 10s + restart cooldown 30s suppress boot-window false
  restarts.
- **Boot auto-start**: `BootReceiver` starts the service on BOOT_COMPLETED
  (logs `fired` / `start() returned OK` / `start() FAILED: <msg>`).
  VERIFIED: reboot → `BootReceiver: fired` → `Server startup complete` →
  `/api/v1/health` 200, no app open. Note: on a PIN-locked phone
  BOOT_COMPLETED fires only after first unlock (~1 min delay). Samsung's
  Sleeping/deep-sleeping lists block boot delivery — keep the app out of
  them (check `settings get secure sleeping_apps`).
- **Zombie-socket fix**: `onDestroy` unregisters the receiver and closes
  the ServerSocket (a leak here left a dead-but-claimed socket).

### 2.4 Admin / uninstall protection (v2.1.0)

- `AdminReceiver` (DeviceAdminReceiver, force-lock policy): when active,
  Android refuses uninstall. Activation intent must NEVER set
  `FLAG_ACTIVITY_NEW_TASK` (AOSP DeviceAdminAdd finishes instantly with a
  blank flash when the calling activity is null).
- `NotificationGuardListener` (NotificationListenerService) auto-dismisses
  the PackageInstaller "uninstalling … unsuccessful" notification —
  universal match on channel (contains `uninstall` + `fail`), no OEM
  package whitelist. Grant via `cmd notification allow_listener`.

### 2.5 Branding

"Hunter's Crescent": Moon Silver `#C8C9D0` crescent, Hunt Crimson
`#B8323A` accent, Void Black `#0A0A0F` background. Launcher icon + dashboard
favicon (`/static/favicon.svg`, hardcoded path — `url_for('static')` raises
`NoMatchFound` on this Starlette).

### 2.6 v2.3.0 — video-call helpers, zero-consent screen capture, deletes, battery

- **Consent-free screen capture (the big one)**: `RemoteControlService`
  (an `AccessibilityService`, `canTakeScreenshot`, API 30+) does the
  capture via `takeScreenshot()` — NO MediaProjection consent dialog, NO
  per-capture prompts, works with the app swiped away. ONE accessibility
  service handles remote-control input AND capture. One-time enable:
  `settings put secure enabled_accessibility_services
  com.example.artemis/com.example.artemis.feature.RemoteControlService`
  (or the phone dashboard's ENABLE button) — survives `install -r` and
  reboot. MediaProjection (`ScreenCaptureService` FGS) remains only as a
  documented fallback (`/screen/status` returns `method`).
- **Live streams (MJPEG/PCM — LEGACY since v2.3.1)**: `GET
  /api/v1/stream/screen` and `/stream/camera?camera=front|back` are
  `multipart/x-mixed-replace` MJPEG streams (per-frame `--frame`
  boundaries, `Connection: close`, no Content-Length); `/stream/mic` is
  raw PCM16 mono 44.1kHz. The browser rendered MJPEG natively in an
  `<img>` — screen ~2.5 fps; camera ~0.5–0.7 fps (CameraX single-capture
  latency on M51), with browser-side rendering artefacts (green/grey
  static on big frames). **LIVE VIEW replaced this with the WebSocket
  pipeline (2.7); the MJPEG endpoints remain for scripted/curl use.**
- **Dashboard LIVE VIEW panel** replaces the old capture button: main
  feed (screen ⇄ back cam toggle), front-camera PiP top-right (toggle),
  mic audio via WebAudio (toggle). All three stop cleanly on STOP or
  page unload (`beforeunload` closes the WebSocket, which ends the
  phone-side loops).
- **Deletes**: `POST /api/v1/sms/delete` + `/logs/calls/delete` by
  provider row id. Call-log delete WORKS on the M51 (`WRITE_CALL_LOG`
  grantable via `pm grant` on Samsung). SMS delete is silently no-op'd
  by Android unless Artemis is the default SMS app — **Artemis must NOT
  become the default SMS app or default dialer (user requirement; the
  stock Samsung apps keep those roles)**, so the API returns a clear
  message instead of a crash and the dashboard copy states the caveat.
- **BatteryHelper**: `GET /api/v1/battery` → levelPercent, isCharging,
  chargeSource, status, health, temperatureC, voltageMv, technology.
  Dashboard status card shows `54% · none · 32.7°C · 3.82V`.
- Streaming server plumbing: `HttpResponse.streamBody` (suspend lambda)
  + `sendStreamHead()` for chunked/streamed bodies; stream handlers run
  on the worker coroutine and close the socket on end (and on client
  disconnect).

### 2.7 v2.3.1 — LIVE VIEW WebSocket pipeline, flip, no-default-app

- **One RFC 6455 WebSocket replaces MJPEG for LIVE VIEW**:
  `GET /api/v1/ws/live` (token-auth via `wsLiveAuth`, same Bearer trust
  as every other route) upgrades the raw socket; framing lives in
  `server/LiveWebSocket.kt` (hand-rolled, NO new Android deps:
  `acceptKey` = Base64(SHA1(key+MAGIC)), unmasked server→client frames,
  masked client→server reads, control frames ping/pong/close, text for
  controls, binary for media).
- **Frame protocol (binary, one frame per media chunk)**:
  `[1B channel][4B big-endian length][JPEG | PCM16]`. Channels:
  `1`=screen JPEG, `2`=back-cam JPEG, `3`=front-cam JPEG (PiP),
  `4`=PCM16 mic (mono 44.1kHz, ~4KB chunks). Client controls are JSON
  text: `{"cmd":"source","v":"screen|cam"}`, `{"cmd":"camera","v":
  "back|front"}`, `{"cmd":"pip","v":"on|off"}`, `{"cmd":"audio","v":
  "on|off"}`.
- **THE v2.3.1 BUG FIX (root cause of the reported static/lag)**: the
  original `encodeFrame` implemented only the 16-bit extended length
  (opcode 126) — full-res screen JPEGs routinely exceed 64 KiB, so the
  length silently wrapped, desyncing the client (websockets lib → 1002
  "invalid opcode", browser canvas → dropped stream / garbled frames).
  **`encodeFrame` now emits the 64-bit extended length (127) for
  payloads > 64 KiB.** Cam-mode frames stayed under 64 KiB, which is why
  only screen mode broke. Verified with a raw-socket frame validator and
  the full browser flow (screen → cam → flip → stop, connection held).
- **Performance**: back cam ≈7.5–8 fps via the persistent 640x480
  `ImageAnalysis` preview binding in `CameraController.kt` (replaces the
  0.5 fps per-frame bind→capture→unbind; photo/frame captures still stop
  the preview first — camera exclusivity). Screen ≈3 fps (accessibility
  `takeScreenshot` latency — inherent). Audio flows continuously.
- **Flip**: dashboard FLIP button toggles `{cmd:"camera"}` back⇄front
  live; `LiveState.camLens` drives both the main feed and the PiP.
- **Dashboard side**: `dashboard_web/server/__init__.py` proxies the
  phone WS (`@app.websocket` → `websockets.connect` with the existing
  cert-pin trust model); `static/js/dashboard.js` parses the binary
  channels, draws to `<canvas>` (`liveMain`/`livePip`), plays audio via
  WebAudio, and holds all state in `liveOn/liveSource/liveLens` +
  `sendWs({cmd})` controls. Cache-busted reloads (`?x=N`) are required
  after JS edits — the browser caches `dashboard.js` aggressively.
- **No default-app dependency (user mandate)**: the SET DEFAULT
  SMS/dialer card is REMOVED from `DashboardScreen.kt`; Artemis never
  takes over calls/SMS — the user's stock Samsung apps keep the default
  roles. SMS-delete remains Android-blocked (provider protection,
  `WRITE_SMS` not grantable) and the UI copy states that caveat.

## 3. Architecture and conventions

### 3.1 Phone server pattern (`Artiest/.../server/SimpleHttpServer.kt`)

- Raw `ServerSocket` on **0.0.0.0:8443**, TLS via `TlsManager`.
- Worker-per-connection: the accept loop spawns a coroutine per
  connection; each request is dispatched through a router.
- Route registration: `router.get("/api/v1/health") { ... }`,
  `router.post(...)`, etc. Authenticated routes wrap the handler in
  `requireAuth(it) { req -> handler(req) }`.
- Handlers return `HttpResponse`; JSON via `jsonResponse(...)`, binary via
  `HttpResponse.binaryBody`.
- **FROZEN SECTOR — DO NOT TOUCH**: `AuthManager.kt`, `TlsManager.kt`, and
  the security enforcement inside `SimpleHttpServer.kt` (token/pin
  verification, pairing-code handling, lockout, rate limiting, constant-time
  compares). ADDING new endpoints is expected; modifying existing auth logic
  is forbidden. Same for `dashboard_web/server/device_client.py` and
  `dashboard/artemis.py` auth paths.
- If a fix seems to REQUIRE touching the frozen sector, STOP and report.

### 3.2 Feature classes (`Artiest/.../feature/`)

One provider class per capability: `LocationTracker`, `CameraController`,
`MicController`, `CallRecorder`, `VideoRecorder`, `CallLogsProvider`,
`SmsProvider`, `DeviceInfoProvider`, `RemoteControlService`,
`CameraFeedController`, `ScreenCaptureController`, `FileSystemHelper`,
`ContactsProvider`, `BatteryHelper`. Handlers in
`SimpleHttpServer` call these. Permissions go through
`permissions/PermissionManager.kt`.

### 3.3 The next phase (helpers)

See `docs/handoff.md` — build server-callable helpers (location, camera
feed, screen, files/assets) that the server can invoke **even when the app
UI is not open** (FGS-only, no Activity dependency).

## 4. Environment & build

- Phone: Samsung Galaxy M51 (SM-M515F), Android 12 / API 31. Reachable at
  **100.91.166.21:8443** (Tailscale) and **192.168.0.102:8443** (WiFi).
- adb: wireless debugging, **port drifts every session** — always
  `adb devices -l` first; reconnect via the phone's Wireless-debugging
  screen (ports usually 30k–50k).
- Build (in `Artiest/`):
  ```
  JAVA_HOME=/opt/android-studio/jbr GRADLE_OPTS="-Djava.version=21" \
    ./gradlew :app:assembleDebug --console=plain -q
  ```
  ANDROID_HOME unset; SDK at `~/Android/Sdk`; aapt2 at
  `~/Android/Sdk/build-tools/36.0.0/aapt2`. APK:
  `Artiest/app/build/outputs/apk/debug/app-debug.apk`.
- Install: `adb -s <ip:port> install -r app-debug.apk` (pairing +
  whitelist survive).
- Dashboard: `cd dashboard_web && python3 main.py` (NOT `python3 -m
  server`). Python 3.12, PEP 668 — venv or `--break-system-packages`.
- No canonical test suite: verification = `assembleDebug` + ad-hoc
  Python/shell checks + live logcat/dumpsys. Version alignment: tag =
  versionName = versionCode = `docs/SECURITY.md` header.
- Log tags: `ArtemisSvc`, `ArtemisServer`, `BootReceiver`, `DozeRecovery`,
  `ArtemisGuard`, `ArtemisApp`.

## 5. Live-test gotchas (learned the hard way)

- **Samsung M51 freezer**: app sockets freeze in Doze AND when the PIN
  keyguard is up; loopback tests time out at TLS handshake. Unlock +
  foreground the app to re-test.
- `svc wifi disable` kills adb-over-wifi — never test connectivity that way.
- `am broadcast SCREEN_ON` from shell is denied — use `input keyevent 26`
  (real power button) to test screen-on re-arm.
- The phone's `HttpURLConnection` REQUIRES `\r\n` line endings —
  `StringBuilder.appendLine()` emits `\n` → silent connection failures.
  Use explicit `\r\n` in `sendHttpResponse()`.
- Dashboards auto-start the service from `MainActivity` (LaunchedEffect) —
  isolate receiver tests with HOME first, or the auto-start masks results.
- Tool output redacts secret-like strings (`Authorization: Bearer ***`);
  verify with python hex when in doubt.
- `pkill -f` patterns self-match the shell — bracket trick:
  `pkill -f "uvicorn [s]erver"`.

## 6. Version history

- **v2.3.0** — helpers phase + video-call: consent-free accessibility
  screen capture, MJPEG screen/camera streams + PCM mic stream, LIVE
  VIEW dashboard panel (PiP front cam, audio toggle), SMS/call-log
  delete, BatteryHelper, camera feed, files, contacts.
- **v2.2.0** (`deb8b11`, tag `v2.2.0`) — 24/7 persistence: battery-opt
  exemption, dynamic Doze-exit re-arm, indefinite wake lock, zombie-socket
  fix, boot auto-start (live-verified after reboot).
- **v2.1.0** (`9e53328`, tag `v2.1.0`) — device-admin uninstall
  protection, uninstall-failure notification guard, Artemis branding
  (icon, favicon).
- **v2.0.0** (tag `v2.0.0`) — background persistence, camera pull, call
  logs, SMS, call/video/mic recorders, admin dashboard UI.
- **v1.4.1** (`577cad6`, tag `v1.4.1`) — TLS 1.3 + TOFU + rotating
  tokens + pairing hardening; Bearer-`***` bug fix.
- Earlier: encrypted-SQLite dashboard store, CLI/DB unification.

Commit convention: messages always `v(version):(message)`; commit + push
on phase completion; work tree clean between phases.
