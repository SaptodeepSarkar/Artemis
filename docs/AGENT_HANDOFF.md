# Artemis Sentinel — Agent Handoff

> Written 2026-07-31 (v2.0.0 phase: background persistence, feature fixes,
> admin-grade UI). Read this FIRST, then `docs/SECURITY.md` (full threat
> model), then the per-module READMEs. It tells you how to regain context in
> ~10 minutes, what is done, and what the next phase looks like.

---

## 1. How to regain context (fast)

### 1.1 The 10-minute read order

1. **This document** — architecture, state, next steps, pitfalls.
2. `docs/SECURITY.md` — the security design that everything else obeys
   (TLS 1.3, TOFU pinning, rotating refresh tokens, replay → revoke).
3. `dashboard_web/README.md` — dashboard run instructions + architecture.
4. `Artiest/app/src/main/java/com/example/artemis/` — read in this order:
   `server/SimpleHttpServer.kt` (protocol + security enforcement),
   `auth/AuthManager.kt` (token lifecycle), `server/TlsManager.kt`,
   `MainActivity.kt` (pairing UX).
5. `dashboard_web/server/db.py` — the encrypted SQLite store (new).
6. `dashboard/artemis.py` — CLI (pairs, controls, downloads).

### 1.2 Repo layout

```
Artemis/
├── Artiest/                      # Android app (Gradle, Kotlin)
│   └── app/src/main/java/com/example/artemis/
│       ├── MainActivity.kt       # pairing UX (6-digit code on screen)
│       ├── auth/AuthManager.kt   # 678 lines: 1h access + rotating 30d refresh,
│       │                         #   replay detection, grace window, legacy migration
│       ├── server/SimpleHttpServer.kt  # raw ServerSocket HTTPS server :8443
│       ├── server/TlsManager.kt        # TLS 1.3 default, restricted 1.2 fallback
│       └── ui/screens/SettingsScreen.kt # paired-dashboards management
├── dashboard_web/                # FastAPI dashboard (port 5000)
│   ├── server/__init__.py        # routes: /api/auth/*, /api/devices, /api/device/...
│   ├── server/db.py              # ★ encrypted SQLite store (devices/media/CLI/known_hosts)
│   ├── server/device_manager.py  # registry over the DB (+ nickname/media API)
│   ├── server/device_client.py   # raw-socket TLS client to the phone
│   ├── server/config.py          # paths, Fernet key, admin password, encryption
│   ├── templates/ + static/      # Stitch "Artemis Labs Brand Identity" UI
│   └── README.md
├── dashboard/artemis.py          # CLI (shares the DB for tokens + pins)
└── docs/SECURITY.md              # v1.4.1 threat model + design
```

### 1.3 The three moving parts

| Part | Where | Speaks | Auth |
|---|---|---|---|
| Phone app | Android, raw `ServerSocket` + TLS on **:8443** | plain HTTP over TLS 1.3 | pairing code → access token (1h) + refresh (30d, rotating) |
| Web dashboard | FastAPI on **127.0.0.1:5000** | HTTP to phone (TLS + TOFU pin) | admin session cookie (1h) |
| CLI | `dashboard/artemis.py` | same client stack as dashboard | token from DB, TOFU pin from DB |

Pairing UX (frozen by user — DO NOT change): open app → see 6-digit code →
enter once → stays paired forever. Code rotates every 5 min, after every
successful pair, and on app restart. Never logged, never sent over the wire.

### 1.4 Environment facts (verified live)

- Phone: Samsung Galaxy M51, **Android 12 / API 31**, "Rekha's Galaxy M51",
  SM-M515F. adb: `adb -s 100.91.166.21:46341` (Tailscale).
  Reachable at **100.91.166.21:8443** (Tailscale) **and 192.168.0.102:8443** (WiFi).
- Dashboard: uvicorn `server.__init__:app` on 127.0.0.1:5000. Admin password:
  `~/.config/artemis/admin_password.txt` (auto-generated, 0600).
- Fernet key: `~/.config/artemis/dashboard_store.key` (0600) — **never delete**;
  it decrypts everything in artemis.db.
- Build: `JAVA_HOME=/opt/android-studio/jbr GRADLE_OPTS="-Djava.version=21"`
  `./gradlew :app:assembleDebug --console=plain -q` (in `Artiest/`).
  ANDROID_HOME unset; SDK at `~/Android/Sdk`; aapt2 at
  `~/Android/Sdk/build-tools/36.0.0/aapt2`. APK:
  `Artiest/app/build/outputs/apk/debug/app-debug.apk` (versionCode 2 / versionName 1.4.1).
- Python 3.12, PEP 668 — use `--break-system-packages` or a venv for new deps.
  Dashboard deps: fastapi, uvicorn, jinja2, cryptography, pydantic.

---

## 2. What is DONE

### 2.1 v1.4.1 security phase (committed `577cad6`, tag `v1.4.1`, pushed)

- **TLS everywhere**: phone server TLS 1.3 default; restricted TLS 1.2
  fallback (ECDHE+AEAD only) for Android < 10 — per user's explicit override.
  Phone TLS key = BouncyCastle software RSA-2048 in filesDir PKCS12
  (AndroidKeyStore RSA breaks ALL Conscrypt handshakes on Samsung, error
  04000044). AndroidKeyStore is used for pairing HMAC only.
- **TOFU pinning**: SHA256 of cert DER, `hmac.compare_digest` constant-time
  compare; mismatch → reject + delete full trust (token, refresh, pin) →
  re-pair required. Dashboard + CLI both pin.
- **Token lifecycle**: access 1h, stateful refresh 30d with rotation, 60s
  grace for legit concurrent refreshes, replay → revoke client + purge.
  Refresh happens before expiry (10-min margin). Expiry NEVER unregisters.
- **Pairing hardening**: code rotation, per-IP lockout 60→120→240→300s cap
  (5 fails/IP → 429), rate limit 120 req/min/IP (loopback exempt),
  client-management endpoints loopback-exempt.
- **Fixed the Bearer-`***` bug** (dashboard_web/server/device_client.py:92):
  previously ALL dashboard↔phone calls sent a literal `***` Authorization
  header — effectively unauthenticated.
- **CLI photo URL http→https** (was plaintext against a TLS-only port).
- Legacy refresh tokens migrated one-time; old-format entries still decrypt.
- Verified live: TLS 1.3 + TLS_AES_256_GCM_SHA384 handshake to the phone,
  pin match, authenticated device/info, pairing rotation, 18/18 mock E2E
  (replay→revoke, grace, at-rest encryption, wrong-pin rejection).

### 2.2 Encrypted-SQLite dashboard phase (committed `675f585` + `b5b8430`, pushed)

- **`dashboard_web/server/db.py`**: SQLite store at
  `~/.config/artemis/artemis.db` replacing `devices.json`, `tokens.json`,
  `known_hosts.json`. Tables: `devices`, `media`, `cli_tokens`, `known_hosts`.
  Fernet-encrypted at rest: device token/refresh_token/cert_fp/nickname,
  media path/note, CLI tokens, TOFU pins. Everything else (timestamps, sizes,
  kinds, model) plaintext.
- **Migration is automatic and destructive-by-design**: on first access the
  legacy JSON files are imported (legacy plaintext re-encrypted), then the
  JSON files are deleted. `.config` now contains ONLY `artemis.db`,
  `dashboard_store.key`, `admin_password.txt` (key + password are required —
  deleting them would destroy the data; do not).
- **Media catalogue**: per-device call recordings / videos / screen
  recordings / screenshots / photos with path, size, duration, timestamp,
  note. API: `GET|POST /api/device/{host}/{port}/media`,
  `DELETE .../media/{id}`. Works with the phone fully offline — the DB is
  the dashboard's own store.
- **Nicknames**: `POST /api/device/{host}/{port}/nickname` (encrypted at
  rest), shown on fleet cards, editable on the per-device page.
- **Offline = BLACK** (user spec): fleet page and per-device dashboard page
  render offline devices with black text + black status dot (ONLINE stays
  green). Fleet page live-probes each paired device (1.5s timeout) per render.
- **CLI now shares the DB** for tokens + TOFU pins (`dashboard/artemis.py`
  bootstraps `dashboard_web/server` via sys.path).
- Verified: 43/43 ad-hoc checks (migration, no-plaintext-on-disk,
  media/nickname CRUD, registry over DB, CLI round-trip), real migration
  confirmed on live files, all API routes exercised live via curl, fleet
  page renders OFFLINE in black (phone currently locked → offline state
  live-verified; ONLINE-green branch is symmetric).

### 2.3 Verification assets (in /tmp — ephemeral, not committed)

- `/tmp/hermes-verify-artemis-db.py` — 43-check DB/migration/encryption harness.
- `/tmp/hermes-verify-artemis-tokenflow.py` — 18/18 token-flow E2E (mock phone).
- `/tmp/artemis_verify_full.py` — one-command live pairing E2E
  (`--host 100.91.166.21 --code <screen code>`; phone must be unlocked +
  app foregrounded).
- `/tmp/artemis_cfg_backup/` — pre-migration copy of the three JSON files.

### 2.4 v2.0.0 phase (tag `v2.0.0`, pushed)

**Scope: keep the app alive in the background, fix the capture features, and
make the app an admin-grade surface. The communication sector (auth/TLS/
pairing, client auth paths) was NOT touched — new endpoints were ADDED only.**

Phone app (`Artiest/`, versionCode 3 / versionName 2.0.0):
- **Background persistence hardened**: `onTaskRemoved()` now explicitly
  re-arms the service when the user swipes the app away (START_STICKY +
  stopWithTask=false as the fallback); `HealthCheckWorker` is now a real
  liveness check (verifies the server socket + process state, restarts the
  service if dead, 15-min periodic); battery-optimization exemption wired
  into SettingsScreen (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  manifest permission added); notification shows server uptime + v2.0.0.
- **Camera fixed for dashboard pulls**: new `GET /api/v1/camera/captures/{id}/file`
  (JPEG bytes), captures persist on disk and are re-scanned on restart,
  capture serialized via a shared camera mutex (photo vs video), front/back
  camera ids accept "front"/"back"/"0"/"1".
- **Call logs**: `GET /api/v1/logs/calls?limit=N` (CallLog provider,
  READ_CALL_LOG) — number, cached name, type, duration, date.
- **SMS**: `GET /api/v1/sms?box=&limit=&includeBody=1` (Telephony provider,
  READ_SMS) — bodies REDACTED unless includeBody=1.
- **Call recorder**: `CallRecorder` (TelephonyManager LISTEN_CALL_STATE +
  MediaRecorder, MIC source fallback — VOICE_CALL is silenced on Samsung),
  runs inside the FGS; `.m4a` stored in filesDir; status/toggle/list/file
  endpoints.
- **Video recorder**: `VideoRecorder` (CameraX VideoCapture, HD quality,
  duration-limited), `.mp4` in filesDir; record/list/get/file endpoints.
- **Mic**: recordings now converted to playable WAV on stop; file endpoint.
- **Admin UI**: `DashboardScreen` rewritten — dark admin theme, server status
  header (up/down, uptime, version), restyled pairing card (flow frozen),
  feature grid with live controls (camera capture, video record 5s, mic
  record/stop, call-recorder toggle, location, info), all driven over the
  loopback-exempt HTTP endpoints. SettingsScreen: battery-opt exemption
  request + version 2.0.0.
- HTTP responses now support binary bodies (`HttpResponse.binaryBody`).

Dashboard (`dashboard_web/`):
- **Bearer-`***` bug fixed** (device_client.py): dashboard↔phone calls were
  sending a literal `***` Authorization header (effectively unauthenticated
  since v1.x). Now sends the real stored token. **This was the actual root
  cause of "nothing works except location + device info".**
- New routes: `GET .../logs/calls`, `GET .../sms`, `POST .../camera/capture/pull`
  (capture on phone → download file → store under
  `~/.config/artemis/captures/<host>_<port>/` → register media row),
  `POST .../video/record`, `GET .../video/list`, `POST .../callrecorder/toggle`,
  `GET .../callrecorder/status`, `GET .../callrecordings`,
  `GET .../media/files/{media_id}` (serves stored files; media list now
  includes `download_url` per entry).
- Media kinds extended with `mic_recording` (db.py).
- Dashboard UI: camera panel has CAPTURE (pull+store) + REC 5S video buttons;
  new CALL LOGS + SMS panels (box selector, include-bodies toggle, redaction
  by default); download links in the media catalogue.

Verified:
- Build: `assembleDebug` clean, APK = versionCode 3 / versionName 2.0.0.
- Mock-phone E2E (dashboard↔phone via the real TLS/TOFU client stack):
  8/8 passed — call logs, SMS, camera capture-pull, video record/list,
  callrecorder status, callrecordings, media download links (photo+video
  fetched through `/media/files/{id}`), TOFU pin enforcement.
- **Verification scare (resolved — no bug shipped)**: tool output renders any
  line containing `Authorization: Bearer {token}` as `Bearer ***` (secret
  redaction), which made the committed client look like it still carried the
  old literal. Hex inspection proved both `_http_request` and
  `_http_download` send the real token; mock E2E 9/9 confirms it (the mock
  phone 401s on anything else). `v2.0.0` was never affected.
- **NOT yet live-verified on the phone** (phone was PIN-locked/dozing —
  Samsung freezer): camera pull, video record, call logs, SMS, call
  recording, swipe-away persistence, screen-off persistence, reboot
  auto-restart. See §3.2 checklist; run with the phone UNLOCKED + app
  foregrounded.

---

## 3. What's NEXT

> v2.0.0 (this document's previous "next phase") is committed — see §2.4.
> Remaining work: **live-verify §3.2/§3.3 on the unlocked phone**, then the
> items below that were deliberately left out of v2.0.0.

### 3.0 v2.0.0 leftovers (not built — flagged, not silently skipped)

- **Screen recording / screenshots** (MediaProjection): requires a per-session
  user consent dialog on Android 10+ — inherent wrinkle, needs a UX decision
  (document it, don't fight it).
- **File-upload variant** of `POST .../media` (multipart phone→dashboard
  push): not needed yet because the dashboard PULLS capture files instead;
  add only if push-based sync is wanted.
- **Live verification on the phone** (§3.2 checklist): swipe-away, screen-off,
  reboot, camera pull, video, call logs, SMS, call recording — phone was
  locked (Samsung freezer) when v2.0.0 shipped; run with phone UNLOCKED +
  app foregrounded.

**Directive from the user (2026-07-31, verbatim intent):**

> "the agent will work on making the app a super user... right now when I
> remove the app from the background the server dies immediately — I want
> the app to be active and serve data in the background. Also this agent
> must work on UI/UX of the app. This app should become admin. Right now
> nothing works except getting location and device info — we have to fix
> the features like camera, call logs etc."

The security + storage backbone (sections 2.1/2.2) is DONE and FROZEN. The
next agent's job is the phone app itself: keep it alive, fix its features,
and make it feel like an admin-grade tool. **Deliverable: commit the work
to GitHub as `2.0.0`.**

### 3.1 HARD RULE — the communication sector is FROZEN. DO NOT TOUCH IT.

The user's explicit instruction: **fix the app WITHOUT touching the
communication sector.** That sector is:

- `Artiest/app/src/main/java/com/example/artemis/auth/AuthManager.kt`
  (pairing, token lifecycle, replay detection, grace window)
- `Artiest/app/src/main/java/com/example/artemis/server/TlsManager.kt`
  (TLS versions, keystore, handshake)
- `Artiest/app/src/main/java/com/example/artemis/server/SimpleHttpServer.kt`
  — the **security enforcement inside it**: token/pin verification,
  pairing-code handling, lockout, rate limiting, constant-time compares.
  Adding NEW capture/log endpoints to this server is expected and allowed,
  but do NOT modify, "improve", or refactor the existing auth/security
  logic. No changes to: pairing UX, code rotation, TLS behavior, token
  storage, revocation semantics.
- `dashboard_web/server/device_client.py` + `dashboard/artemis.py` client
  auth paths (unless you are fixing a NEW endpoint's wiring).

If a fix seems to REQUIRE touching the frozen sector, STOP and report back
instead of changing it.

### 3.2 Priority 1 — Background persistence (the server must NOT die)

Current failure: swiping the app away from recents kills the process and
the :8443 server dies instantly. The app must run and serve data in the
background, screen off, app swiped away, and across reboots.

- **Foreground service** owns the `ServerSocket`, NOT the activity:
  `android:foregroundServiceType`, persistent notification (channel id,
  low importance so it can be hidden from the user if desired), `startForeground()`
  within seconds of `onCreate`.
- `START_STICKY` (or `START_REDELIVER_INTENT`) + `onTaskRemoved()` →
  restart the service when the user swipes the app away.
- `PARTIAL_WAKE_LOCK` while the server is listening; release on destroy.
- **Doze / battery optimization**: request exemption
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), keep-alive via
  `AlarmManager` (setExactAndAllowWhileIdle) or WorkManager periodic
  ping that re-arms the socket if dead. Note the Samsung M51 is aggressive
  (see §4 freezer pitfall) — expect to fight this.
- **Boot receiver** (`RECEIVE_BOOT_COMPLETED`) to restart the service
  after reboot (pairing + keystore survive — no re-pair).
- Verify: swipe app from recents → `curl https://<phone>:8443/health`
  still answers; screen off 30+ min → still answers; reboot → auto-restarts.

### 3.3 Priority 2 — Fix the features (currently only location + device info work)

The dashboard's per-device page already has buttons/panels for these —
they mostly 4xx/timeout because the phone side is missing or broken.
Fix or build each, end-to-end:

| Feature | Phone-side work | Notes |
|---|---|---|
| **Camera capture** | `POST /camera/capture` EXISTS but reportedly doesn't work — debug live (permissions, camera reopen after release, response shape) until a photo lands on the dashboard | Highest priority — it's already wired in the UI |
| **Call logs** | New route `GET /logs/calls` → `CallLog.Calls` content provider (number, type, duration, timestamp) | Needs `READ_CALL_LOG` |
| **SMS** | New route `GET /sms` → Telephony SMS provider | Needs `READ_SMS`; redact bodies unless user wants them |
| **Call recording** | `MediaRecorder` (MIC or VOICE_CALL source), trigger on `TelephonyManager` call state; store `.m4a` in filesDir | VOICE_CALL may be silenced on Samsung — fall back to MIC; MUST run inside the foreground service (§3.2) |
| **Video recording** | `MediaRecorder` + Camera2/CameraX, dashboard-triggered | Camera is exclusive — release before screen capture |
| **Screen recording** | `MediaProjection` — **Android 10+ needs a user consent dialog per session** | Consent wrinkle is inherent; document it, don't fight it |
| **Screenshots** | `MediaProjection` virtual display → `ImageReader` → PNG, or AccessibilityService | Same consent caveat |
| **Location + device info** | Already work — regression-guard them | |

After each capture, register the metadata row on the dashboard via the
existing `POST /api/device/{host}/{port}/media` (the DB + media panel are
already built and verified) so files show up in the catalogue. Add a
file-upload variant of that endpoint (multipart → store bytes under
`~/.config/artemis/captures/<device>/`, then register the media row) and a
download link per entry on the dashboard.

### 3.4 Priority 3 — App UI/UX ("this app should become admin")

- The app should feel like a professional admin/super-user surface, not a
  dev toy: dark theme consistent with the dashboard, a status screen
  (server up/down, uptime, service state, last seen, paired dashboards),
  feature toggles, and clear states for each capture feature.
- The pairing screen is FROZEN (open → 6-digit code → enter once). You may
  restyle it, not rework its flow.
- `SettingsScreen.kt` exists for paired-dashboard management — extend, don't
  break.

### 3.5 UX + architecture constraints that MUST NOT change

- Open app → see code → enter once → paired forever. No re-pairing on
  upgrade (keystore survives `install -r`).
- Pairing code never logged / never networked.
- Offline = black, ONLINE = green on the dashboard (user's color spec).
- `.config` contains only the DB + key + admin password.
- Token expiry never unregisters a device.

### 3.6 Versioning, testing, delivery

- Bump `Artiest/app/build.gradle.kts` to **versionName "2.0.0"** and
  **versionCode 3** (strictly increasing; current = versionCode 2).
- Align the header of `docs/SECURITY.md` ("Version 2.0.0 · …") — that file
  documents the DESIGN, which is unchanged; only the version line moves.
- Testing loop:
  1. Build: `JAVA_HOME=/opt/android-studio/jbr GRADLE_OPTS="-Djava.version=21"`
     `./gradlew :app:assembleDebug --console=plain -q` (in `Artiest/`).
  2. Install: `adb -s 100.91.166.21:46341 install -r app-debug.apk`
     (pairing survives `install -r`).
  3. Phone must be UNLOCKED with app foregrounded for live tests (Samsung
     M51 freezer, §4). Re-verify pairing with
     `python3 /tmp/artemis_verify_full.py --host 100.91.166.21
     --code <code-from-screen>`.
  4. Per feature: trigger from the dashboard, confirm the file/row lands
     and the DB value is `enc:`-prefixed (encrypted at rest).
  5. Background tests (§3.2): swipe-away, screen-off, reboot.
- **Deliverable:** commit to GitHub **as `2.0.0`** (tag `v2.0.0`),
  work tree clean, handoff doc updated. Do NOT touch the frozen sector
  (§3.1) — if you believe a fix needs it, stop and report.

---

## 4. Pitfalls & lessons (learned the hard way — respect them)

- **Samsung freezer**: Doze (screen off) AND PIN keyguard both freeze the
  app's sockets. Whitelisting (standby bucket, deviceidle, appops
  RUN_IN_BACKGROUND, freecess_whitelist) does NOT help while locked.
  Unlock + foreground the app for tests.
- **`\r\n` line endings are MANDATORY** in `sendHttpResponse()` on the phone:
  `appendLine()` emits `\n` → silent connection failures (blank pairing code
  bug). Use explicit `\r\n`.
- **AndroidKeyStore RSA keys fail ALL Conscrypt TLS handshakes on Samsung**
  (04000044). TLS key = BouncyCastle software RSA-2048 PKCS12 in filesDir.
  AndroidKeyStore stays for pairing-HMAC only.
- **`save_devices`/`put_device` must COPY dicts before encrypting** — mutating
  the live registry `__dict__` swaps the working token for ciphertext → 401s.
  (`db.put_device` copies internally; keep it that way.)
- **Fernet key is the whole game**: `dashboard_store.key` decrypts tokens,
  refresh tokens, pins, nicknames, media paths. Losing it = re-pair everything.
- **Legacy plaintext passes through decryption** (`enc:` prefix = encrypted,
  else plaintext). Migration re-encrypts plaintext values on import.
- Dashboard API paths are `/api/auth/*` and `/api/devices`,
  `/api/device/{host}/{port}/...` — NOT `/api/v1/...`. `host` and `port` are
  separate path segments (never `host:port` in one segment).
- Rate limit 120 req/min/IP, loopback exempt; pairing lockout
  60→120→240→300s cap per IP.

## 5. Housekeeping

- Git: work tree must stay clean; commit + push when a phase completes.
  v1.4.1 = tag `v1.4.1` = versionName "1.4.1" = versionCode 2 = SECURITY.md
  header — keep them aligned for releases. **Next release: 2.0.0**
  (versionCode 3, tag `v2.0.0`) — the app-persistence/features/UI phase.
- `.gitignore` covers `__pycache__/`, `*.pyc`.
- No credentials appear anywhere in the repo or in this doc — pairing codes,
  tokens, pins and passwords are runtime state in `~/.config/artemis/` only.
