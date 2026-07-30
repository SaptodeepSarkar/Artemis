# Artemis Sentinel — Session Memory (2026-07-30 v1.2.0)

## Status: SERVER WORKS ON SAMSUNG M51

Raw `ServerSocket` HTTP server (SimpleHttpServer.kt) replaces Ktor/Netty successfully.
Server binds, serves HTTP, pairs, and authenticates correctly.

## The Pairing Problem (FIXED THIS SESSION)

Root cause: `DashboardScreen.kt` fetched pairing code BEFORE starting the service
(LaunchedEffect order bug). Fetch always failed → fell back to `SecureRandom` local
generation → phone showed fake code that mismatched the server's real code.

Fix: start service first, `delay(3000)` for server readiness, then fetch real code
from `GET http://127.0.0.1:8443/api/v1/auth/pairing-code`.

Also removed `SecureRandom` fallback entirely — phone now only shows the server's actual
pairing code or `------` if unreachable.

## Connection
- Phone: 100.91.166.21 (Tailscale) / 192.168.0.102 (LAN)
- ADB: 192.168.0.102:43089
- Dashboard: `python3 dashboard/artemis.py pair <ip> --code <code> --save`
- Web dashboard planned (Flask/FastAPI)

## Build
```bash
JAVA_HOME=/opt/android-studio/jbr GRADLE_OPTS="-Djava.version=21" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.artemis
adb shell am start -n com.example.artemis/.MainActivity
```

## Architecture Decisions (v1.2.0+)
- **No Ktor/Netty** — raw `java.net.ServerSocket` avoids ART reflection crashes
- **Manual JSON builders** — `kotlinx.serialization` breaks on `Map<String, Any?>`
- **Cleartext HTTP** — no TLS (LAN only, for now)
- **Service auto-starts** from DashboardScreen LaunchedEffect
- **Pairing code = server authority** — UI is pure API consumer, no local generation

## What's Next (Roadmap)
See AGENTS.md for v2.0 roadmap.
