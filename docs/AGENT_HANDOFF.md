# Artemis Sentinel — Agent Handoff

> Written 2026-07-31 (v1.4.1 + encrypted-SQLite dashboard phase).
> Read this FIRST, then `docs/SECURITY.md` (full threat model), then the
> per-module READMEs. It tells you how to regain context in ~10 minutes,
> what is done, and what the next phase (implement the app's capture
> features) looks like.

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

### 2.2 Encrypted-SQLite dashboard phase (this session — commit pending)

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

---

## 3. What's NEXT: implement the app (the actual capture features)

The security + storage backbone is done. The phone app currently only pairs,
serves health/device info, and exposes a camera capture endpoint. The next
phase is the meat: **capture features on the phone, streaming files to the
dashboard's media catalogue.**

### 3.1 Phone-side features to build (in `Artiest/`)

| Feature | Android APIs / approach | Pitfalls |
|---|---|---|
| **Call recording** | `MediaRecorder` (MIC or VOICE_CALL source), trigger via `TelephonyManager` call state; store `.mp3/.m4a` in app filesDir | VOICE_CALL needs RECORD_AUDIO + call state permission; Samsung may silence VOICE_CALL — fall back to MIC; must run in foreground service |
| **Video recording** | `MediaRecorder` + `Camera2` (or CameraX), user-facing "record video" command from dashboard | Camera is exclusive — release before screen capture; doze |
| **Screen recording** | `MediaProjection` (API 21+, but **Android 10+ requires user consent dialog per session**) | The consent dialog is a UX wrinkle for a remote-control RAT — user must tap "Start now" once per projection; document this. `MediaProjectionManager.createScreenCaptureIntent()` |
| **Screenshots** | `MediaProjection` (virtual display → ImageReader) or `AccessibilityService` | Same consent caveat; ImageReader → Bitmap → PNG |
| **Photo capture** | Already exists: `POST /api/device/{host}/{port}/camera/capture?camera_id=...` on the phone + dashboard button | — |
| **File delivery** | After capture, POST the file to the dashboard (new endpoint, see 3.3) | Must be authenticated (bearer token) + TLS + pinned, like everything else |

### 3.2 Server-side pieces to add (phone, `SimpleHttpServer.kt`)

- New routes: `POST /capture/call` , `POST /capture/video`,
  `POST /capture/screen`, `POST /capture/screenshot`, `GET /captures` (list
  on-device files). Each returns `{capture_id, path, size, duration}`.
- Keep the security invariants: auth via bearer token, lockout/rate limits,
  no exception leakage, `\r\n` line endings in `sendHttpResponse()`.
- Captures land in `filesDir/captures/<type>/...` (survives `install -r`).

### 3.3 Dashboard-side ingest (mostly built — needs the file endpoint)

- `POST /api/device/{host}/{port}/media` already registers metadata; add a
  file-upload variant (multipart) that stores the bytes under
  `~/.config/artemis/captures/<device>/...` (or the DB as BLOB — prefer
  files + DB metadata) and then registers the media row.
- The media panel UI already renders the catalogue; add a download link per
  entry (serve via an authenticated route).
- Optionally: a "capture now" panel on the per-device dashboard page
  (buttons: record call, screenshot, etc.) hitting the new phone routes.

### 3.4 UX constraints that MUST NOT change

- Open app → see code → enter once → paired forever. No re-pairing on
  upgrade (keystore survives `install -r`).
- Pairing code never logged / never networked.
- Offline = black, ONLINE = green (user's explicit color spec).
- `.config` contains only the DB + key + admin password.
- Token expiry never unregisters a device.

### 3.5 Testing the next phase

1. Build + install: `adb -s 100.91.166.21:46341 install -r .../app-debug.apk`
   (pairing survives).
2. Phone must be UNLOCKED with app foregrounded for any live test — Samsung
   M51 freezes app sockets in Doze AND under the PIN keyguard (adb-forward
   tests then time out at the TLS handshake). `svc power stayon true` helps
   only when unlocked.
3. Live pairing E2E: `python3 /tmp/artemis_verify_full.py --host 100.91.166.21
   --code <code-from-screen>`.
4. After capture features: verify a file lands on the dashboard, the media
   row appears (path/size/duration), and the DB row is encrypted at rest
   (`python3 -c "import sqlite3;... SELECT path FROM media"` → `enc:` prefix).

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
  header — keep them aligned for releases.
- `.gitignore` covers `__pycache__/`, `*.pyc`.
- No credentials appear anywhere in the repo or in this doc — pairing codes,
  tokens, pins and passwords are runtime state in `~/.config/artemis/` only.
