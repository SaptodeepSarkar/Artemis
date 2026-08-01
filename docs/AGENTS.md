# Artemis Sentinel — AGENTS.md

> Current: **v2.2.0** (2026-08-01). Read this first, then `docs/SECURITY.md`
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

## 2. Current state (v2.2.0 — shipped, live-verified)

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
| `/api/v1/logs/calls`, `/api/v1/sms` | ✓ |
| `/api/v1/callrecorder/*`, `/api/v1/callrecordings/*` | ✓ |
| `/api/v1/video/record`, `/list`, `/{id}`, `/{id}/file` | ✓ |
| `/api/v1/admin/lock` (device-admin lockNow) | ✓ |
| Screen recording / screenshots | ✗ NOT built (MediaProjection needs per-session consent on Android 10+) |
| File browser (list/download/upload) | ✗ NOT built |

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
`SmsProvider`, `DeviceInfoProvider`, `RemoteControlService`. Handlers in
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
