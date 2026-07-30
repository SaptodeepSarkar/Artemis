# Artemis Sentinel — Project Handoff

## Current State (2026-07-30)

### What Works
- **Build**: Gradle 9.5.0 + AGP 9.3.1 + Kotlin 2.2.10. `./gradlew assembleDebug` succeeds.
- **APK**: 87.7 MB, installed on Samsung Galaxy M51 (Android 13, Tailscale 100.91.166.21).
- **Netty init**: Server starts successfully (confirmed via logcat). All plugins install: ContentNegotiation, CORS, StatusPages, WebSockets, Authentication, Routes.
- **Logging**: Extensive `android.util.Log` at every step (tags: ArtemisApp, Dashboard, ArtemisSvc, ArtemisServer).

### What's Blocked — Priority for Next Session

#### 1. Ktor/Netty Server Binds but Dashboard Can't Connect (MUST FIX)
The server reports "started successfully on port 8443" but accepting no HTTP traffic:
- TCP connection to 100.91.166.21:8443 succeeds (confirmed via raw socket)
- But HTTP requests get no response (empty data received, then connection closed)
- Netty NIO transport on Android 13 Samsung is broken despite `noKeySetOptimization=true`
- CIO engine is also broken (accepts TCP, never parses HTTP)

**Fix**: Replace entire Ktor+Netty stack with `java.net.ServerSocket` based HTTP server. This avoids all Android NIO/Netty issues. See `server/ArtemisServer.kt` for the route architecture to replicate.

#### 2. Pairing Code Decoupled from Server (MUST FIX)
The pairing code shown in the UI is generated at `DashboardScreen.kt:155` with `java.security.SecureRandom().nextInt(1_000_000)`. It is:
- Randomly generated on each refresh
- Displayed to user
- Never sent to the `AuthManager` or the server's `/api/pair` endpoint

The server's `/api/pair` endpoint (in `AuthRoutes.kt`) expects to validate a pairing code, but neither side knows the other's code.

**Fix**: The server should generate the pairing code and expose it via an endpoint (e.g. `GET /api/v1/pairing/code`). The UI fetches it from there. Or the AuthManager generates it and the UI reads it from the ViewModel.

#### 3. Service Dies on Screen Navigation (MUST FIX)
When user navigates to Settings or app goes to background:
- `startForegroundService()` is used but the service isn't properly **bound**
- `handleAppVisibility mAppVisible=true visible=false` (app backgrounded)
- New DecorView created when returning (SettingsScreen replaces DashboardScreen)
- Server process continues (PID persists) but server stops listening

**Fix**: Use `bindService()` alongside `startForegroundService()`. Keep a reference to the service from the Activity/Fragment. Or use WorkManager to restart the server on navigation changes.

### Files to Focus On

| File | Purpose | Priority |
|---|---|---|
| `app/src/main/java/com/example/artemis/server/ArtemisServer.kt` | Replace Ktor+Netty with raw ServerSocket | HIGH |
| `app/src/main/java/com/example/artemis/server/routes/*.kt` | Route handlers (keep, just change API surface) | HIGH |
| `app/src/main/java/com/example/artemis/ui/screens/DashboardScreen.kt` | Fix pairing code to come from server | HIGH |
| `app/src/main/java/com/example/artemis/service/ArtemisSentinelService.kt` | Add bindService pattern | MEDIUM |
| `app/src/main/java/com/example/artemis/auth/AuthManager.kt` | Generate pairing code, expose it | HIGH |
| `dashboard/artemis.py` | Already works, connects to port 8443 | - |

### Build Commands
```bash
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk
export GRADLE_OPTS="-Djava.version=21"
cd ~/Projects/Artemis/Artiest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.artemis/.MainActivity
```

### Logcat Filter for Debugging
```bash
adb logcat -s ArtemisApp:V Dashboard:V ArtemisSvc:V ArtemisServer:V
```

### Test Connection (from desktop)
```bash
cd ~/Projects/Artemis/dashboard
timeout 5 python3 -c "
import socket
s = socket.socket()
s.settimeout(5)
s.connect(('100.91.166.21', 8443))
s.send(b'GET /api/v1/health HTTP/1.0\r\nHost: 100.91.166.21\r\n\r\n')
import time; time.sleep(1)
print(repr(s.recv(4096)))
s.close()
"
```

### Key Learnings
- **Netty on Android 13 Samsung**: The `noKeySetOptimization=true` and `noUnsafe=true` properties prevent crashes but don't fix the silent failure. Raw `ServerSocket` is the reliable choice.
- **CIO engine**: Accepts TCP connections, never processes HTTP. Pure Kotlin coroutine NIO2 engine has issues on Samsung Android 13.
- **Ktor dependencies can be removed**: `ktor-server-core`, `ktor-server-netty`, `ktor-server-cio`, `ktor-server-websockets`, `ktor-server-content-negotiation`, `ktor-server-auth`, `ktor-server-cors`, `ktor-server-status-pages`, `ktor-serialization-json`. Replace with stdlib HTTP server.
- **Build complexity**: AGP 9.3.1 provides built-in Kotlin compilation. Do NOT apply the `kotlin-android` Gradle plugin (conflicts with Gradle 9.5 `kotlin-dsl`).
- **SQLCipher removed**: Switched to plain `SQLiteOpenHelper` to avoid build issues.
- **Notification permission**: Android 13+ needs `POST_NOTIFICATIONS` runtime permission for `startForeground()`. Not yet handled.
