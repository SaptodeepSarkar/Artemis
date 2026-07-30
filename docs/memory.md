# Artemis Sentinel — Session Memory (2026-07-30)

## Status
Server **starts** (confirmed logcat: "Server started successfully on port 8443") but **dashboard cannot connect**. Pairing code is UI-only lie. Service dies on screen nav.

## Critical Issues (next session)
1. **Netty/CIO broken on Android 13 Samsung** — both accept TCP but never respond. Replace with raw `ServerSocket` HTTP server.
2. **Pairing code decoupled** — generated in UI, never sent to server. Fix: server generates it, exposes via API.
3. **Service dies on navigation** — no `bindService()`. Fix: bindService + keep ref.

## Connection
- Phone: 100.91.166.21 (Tailscale)
- ADB: 192.168.0.102:43089
- Dashboard: `~/Projects/Artemis/dashboard/artemis.py pair 100.91.166.21 --code <code>`

## Build
```bash
JAVA_HOME=/opt/android-studio/jbr GRADLE_OPTS="-Djava.version=21" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.artemis/.MainActivity
```

## Logcat
```bash
adb logcat -s ArtemisApp:V Dashboard:V ArtemisSvc:V ArtemisServer:V
```

## Files
- Server: `Artiest/app/src/main/java/com/example/artemis/server/ArtemisServer.kt`
- Routes: `Artiest/app/src/main/java/com/example/artemis/server/routes/*.kt`
- UI: `Artiest/app/src/main/java/com/example/artemis/ui/screens/DashboardScreen.kt`
- Service: `Artiest/app/src/main/java/com/example/artemis/service/ArtemisSentinelService.kt`
- Auth: `Artiest/app/src/main/java/com/example/artemis/auth/AuthManager.kt`
- Dashboard CLI: `dashboard/artemis.py`

## Netty Properties (required for Android 13)
`io.netty.noKeySetOptimization=true`, `io.netty.noUnsafe=true` set in `ArtemisApp.onCreate()` and `ArtemisServer.init`.
