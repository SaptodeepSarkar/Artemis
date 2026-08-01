# Artemis Sentinel — Handoff: Server-Callable Helpers

> Written 2026-08-01, after v2.2.0 (`deb8b11`) shipped and was pushed.
> THIS is the current phase brief. Read `docs/AGENTS.md` first (project
> reference + frozen-sector rules), then `docs/SECURITY.md` (threat model),
> then this document, then the source.

---

## 1. The mission

**Build a set of server-callable "helpers" — location, camera feed,
screen, files/assets, etc. — and expose them as authenticated endpoints on
the phone's `SimpleHttpServer` (`:8443`), so they work even when the app
UI is NOT open (the foreground service keeps the server alive; the helpers
must not depend on an Activity).**

User directive (verbatim intent):

> "start building helpers like the location helper, camera feed helper,
> screen helper, files/assets helper, etc., so that the server can call
> them even if the app is not opened."

## 2. What exists already (do NOT rebuild these — extend or wire them)

The phone app already has one provider class per capability, all under
`Artiest/app/src/main/java/com/example/artemis/feature/`:

| Capability | Provider | Endpoints live today |
|---|---|---|
| Location | `LocationTracker.kt` | `GET /api/v1/location/current`, `GET /api/v1/location/history` |
| Camera (single shot) | `CameraController.kt` | `GET /api/v1/camera/list`, `POST /api/v1/camera/capture`, `GET /api/v1/camera/captures`, `GET /api/v1/camera/captures/{id}`, `GET .../{id}/file` |
| Mic | `MicController.kt` | `POST /api/v1/mic/record/start`, `.../stop`, `GET /api/v1/mic/recordings`, `.../{id}`, `.../{id}/file` |
| Call recorder | `CallRecorder.kt` | `POST /api/v1/callrecorder/toggle`, `GET .../status`, `GET /api/v1/callrecordings`, `.../{id}`, `.../{id}/file` |
| Video recorder | `VideoRecorder.kt` | `POST /api/v1/video/record`, `GET /api/v1/video/list`, `GET /api/v1/video/{id}`, `GET .../{id}/file` |
| Call logs | `CallLogsProvider.kt` | `GET /api/v1/logs/calls` |
| SMS | `SmsProvider.kt` | `GET /api/v1/sms` |
| Device info | `DeviceInfoProvider.kt` | `GET /api/v1/device/info`, `.../battery`, `.../network`, `.../storage` |

The server pattern to follow (see `server/SimpleHttpServer.kt`):

- Raw `ServerSocket` + TLS on **8443**, worker-per-connection coroutines.
- Route registration with `requireAuth`: `router.get("/api/v1/...") {
  requireAuth(it) { req -> handler(req) } }`.
- Handlers return `HttpResponse`; JSON via `jsonResponse(...)`, binary via
  `HttpResponse.binaryBody` (already used for camera/mic/video files).
- Permissions requested/checked via `permissions/PermissionManager.kt`.

## 3. What to build (the helpers)

### 3.1 Helper pattern (house style)

Create/refactor a `helper/` layer (or keep in `feature/` — your call, but
be consistent) where each helper:

1. Is a plain Kotlin class/object taking `Context` (application or
   service context — NEVER an Activity reference; it must run with the app
   closed).
2. Exposes suspend or blocking methods returning plain data (or a file
   path) — no HTTP knowledge inside the helper.
3. Has its endpoints wired in `SimpleHttpServer` via `requireAuth`, with
   `jsonResponse`/binary responses.
4. Handles its own permission state (return a clear
   `{"error":"permission_denied","permission":"..."}` instead of crashing).

### 3.2 Required helpers (user-named)

1. **Location helper** — exists (`LocationTracker` + current/history
   endpoints). Extend: verify it returns a fix even after the app was
   never opened post-reboot (FGS-only path); consider a `/location/stream`
   or higher-frequency poll variant for the dashboard map. Low priority —
   mostly verification + polish.

2. **Camera feed helper** — exists as single-shot capture. Build the
   **feed**: a repeating capture (e.g. `POST /api/v1/camera/feed/start`
   with interval + duration, `.../feed/stop`) returning the latest frame
   via `GET /api/v1/camera/feed/latest` (binary JPEG) so the dashboard can
   render a live-ish view. Reuse `CameraController`'s mutex discipline
   (camera is exclusive — photo vs video vs feed must serialize).

3. **Screen helper** — NOT built (flagged leftover from v2.0.0).
   Screenshots / screen recording need `MediaProjection`, and Android 10+
   REQUIRES a per-session user consent dialog. Decide and document the UX:
   a one-time consent on the phone (from a foreground Activity —
   `MainActivity`/`DashboardScreen` trigger) that caches the
   `MediaProjection` token, then the helper captures on demand. If the
   consent wrinkle is unacceptable, document why and propose the
   AccessibilityService alternative — but do NOT silently skip.

4. **Files/assets helper** — NOT built. New endpoints:
   - `GET /api/v1/files/list?path=<abs>` — directory listing (name, size,
     mtime, isDir).
   - `GET /api/v1/files/download?path=<abs>` — file bytes (binary body).
   - `POST /api/v1/files/upload?path=<abs>` — write bytes (from dashboard
     push; needs a body-parsing path in the server — check how `POST`
     bodies are handled today and mirror it).
   - Whitelist/restrict the root to app-accessible storage (filesDir +
     public dirs) unless the user asks for full-root.

5. **"Etc." candidates** (pick what fits): contacts
   (`ContactsContract`, `READ_CONTACTS`), calendar, clipboard
   (`ClipboardManager`), active app / foreground-app info, camera
   front/back switch already supported, ambient light / sensors. Wire the
   ones the dashboard already has buttons for first.

### 3.3 Dashboard side (only if time permits)

`dashboard_web/` per-device page gains buttons/panels for the new
endpoints, following the existing camera/call-logs panel pattern
(`device_client.py` calls → `templates/` render). The phone side is the
priority; dashboard wiring is secondary.

## 4. Hard rules

- **FROZEN sector** (from `docs/AGENTS.md` §3.1): `AuthManager.kt`,
  `TlsManager.kt`, and the security enforcement inside
  `SimpleHttpServer.kt` — token/pin verification, pairing, lockout, rate
  limiting, constant-time compares. ADD new endpoints; never modify
  existing auth logic. Same for `device_client.py` / `artemis.py` auth
  paths. If a fix seems to require touching them, STOP and report.
- **Pairing UX frozen**: open app → 6-digit code → enter once → paired
  forever. No re-pair on upgrade.
- **Helpers must run FGS-only**: the server must answer these endpoints
  with the app swiped away / never opened after reboot — that is the
  acceptance test.
- No Activity references in helpers; no `androidx.compose` imports in the
  helper/server layer.

## 5. Verification checklist

1. `assembleDebug` clean; version bump in `app/build.gradle.kts` +
   `docs/SECURITY.md` header (tag = versionName = versionCode).
2. `adb install -r` (pairing + whitelist survive) on the M51.
3. Live tests with the phone **unlocked + app foregrounded first** (M51
   freezer), then repeat with the app **swiped away / not opened**:
   - `curl -k https://100.91.166.21:8443/api/v1/health` → 200.
   - Each new endpoint returns 200 with correct JSON/binary; wrong/missing
     token returns 401 (`requireAuth` working).
   - Screen-off 30+ min → `/health` still answers (Doze-exit re-arm).
   - Reboot → server auto-starts (BootReceiver) → helpers answer without
     opening the app.
4. Register captured files as media rows on the dashboard via
   `POST /api/device/{host}/{port}/media` so they appear in the catalogue.

## 6. Delivery

- Commit + push to GitHub (`master`), message `v2.3.0:...` (helpers:
  camera feed, screen, files/assets, etc. — name what shipped), tag
  `v2.3.0`, work tree clean.
- Update this file: move done items to a DONE section, write the next
  phase brief.
- Update `docs/AGENTS.md` §2.2 feature table + §2.3/§2.4 as needed.
