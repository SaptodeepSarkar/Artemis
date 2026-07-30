# Artemis Sentinel

A remote Android device monitoring and management application, accessible over Tailscale via a desktop CLI dashboard.

## Architecture

- **Android app** — Kotlin + Compose UI. Foreground service with embedded HTTP server.
- **Server** — Ktor/Netty on port 8443 (TLS). WebSocket streams for camera/mic. REST API for device info, location, contacts.
- **Dashboard** — Python CLI client (`dashboard/artemis.py`) with zero external dependencies.
- **Auth** — Ed25519 challenge-response → JWT over TLS.
- **Data** — SQLite (locations, contacts, messages, call logs, accounts, apps, media).

## Stack

| Layer | Tech |
|---|---|
| Language | Kotlin 2.2.10 |
| Build | AGP 9.3.1, Gradle 9.5.0 |
| UI | Jetpack Compose (BOM 2026.02.01) |
| Server | Ktor 2.3.12 (Netty engine) |
| Min SDK | API 29 (Android 10) |
| Target SDK | 37 (Android 16) |

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

# Connect dashboard
cd dashboard && python3 artemis.py pair <phone-tailscale-ip> --code <pairing-code>
```

## Current Status

- App builds and installs cleanly
- Server starts and binds on port 8443
- **Known issue**: Ktor/Netty HTTP processing is broken on Samsung Android 13 — server accepts TCP but drops all HTTP traffic. Pending fix: replace with raw `ServerSocket` implementation.

## Docs

See the [`docs/`](./docs/) folder for:
- `AGENTS.md` — Full project handoff & open issues
- `PRD.md` — Product requirements
- `SYSTEM.md` — System design
- `PROJECT.md` — Project overview
- `TASKS.md` — Task tracker
- `memory.md` — Session memory / quick reference

## License

MIT
