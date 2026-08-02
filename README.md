# Artemis Sentinel

A self-hosted, LAN/Tailscale remote Android monitoring and management
system. The phone runs a raw `ServerSocket` + TLS HTTPS server ("RAT" in
the project's own terms) inside a 24/7 foreground service; a web dashboard
and a Python CLI control it.

## Architecture

- **Android app** (`Artiest/`, Kotlin + Compose) — raw `ServerSocket` +
  TLS HTTPS server (self-signed, TOFU-pinned) on port **8443**, kept alive
  by a foreground service. Authenticated REST + `ws/live` (WebSocket)
  endpoints for device info, location, camera, mic, call logs, SMS,
  video, screen capture, accessibility remote input, and triple RECORD.
- **Server** — hand-rolled HTTP + RFC 6455 WebSocket on a raw socket (NO
  Ktor). TLS via `TlsManager`, auth via HMAC access/refresh tokens + 6-digit
  pairing, rate limiting + lockout.
- **Web dashboard** (`dashboard_web/`) — FastAPI/Starlette fleet view,
  per-device controls, LIVE VIEW, media catalogue, encrypted SQLite store.
- **CLI** (`dashboard/artemis.py`) — zero-dependency Python client; pairs,
  queries, downloads; shares the dashboard DB.
- **Auth** — 6-digit pairing code → rotating refresh token (30 d) → 1 h
  access token (HMAC-SHA256) over TLS 1.3.

## Stack

| Layer | Tech |
|---|---|
| Language | Kotlin 2.2.10 |
| Build | AGP 9.3.1, Gradle |
| UI | Jetpack Compose (BOM 2026.02.01) |
| Server | raw `ServerSocket` + TLS + hand-rolled WS (no Ktor) |
| Encryption | TLS 1.3, TOFU cert pin, BouncyCastle software RSA-2048 key |
| Min SDK | API 29 (Android 10) |
| Target SDK | 37 (Android 16) |
| Dashboard | FastAPI/Starlette + encrypted SQLite (Fernet) |

## Quick Start

```bash
# Build
export JAVA_HOME=/opt/android-studio/jbr
export GRADLE_OPTS="-Djava.version=21"
cd Artiest && ./gradlew assembleDebug

# Install on device (ADB)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.example.artemis/.MainActivity

# Web dashboard
cd dashboard_web && python3 main.py   # -> :5000

# Connect CLI
cd dashboard && python3 artemis.py pair <phone-tailscale-ip> --code <pairing-code>
```

## Current Status

- App builds and installs cleanly; server binds on **0.0.0.0:8443** (TLS).
- LIVE VIEW (camera), accessibility remote-control, and one-button triple
  RECORD (screen / front / rear) are implemented.
- 24/7 persistence: battery-opt exemption, Doze-exit re-arm, boot
  auto-start, admin/device-admin uninstall protection.

## Docs

Three documents in [`docs/`](./docs/):
- `AGENTS.md` — durable project reference & current state
- `SECURITY.md` — threat model, TLS, token, pairing design
- `handoff.md` — the current phase brief for the next agent

## License

MIT