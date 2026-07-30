# Artemis Sentinel — Project Handoff

## Current State (2026-07-30 v1.2.0)

### What Works
- **Build**: Gradle 9.5.0 + AGP 9.3.1 + Kotlin 2.2.10. `./gradlew assembleDebug` succeeds.
- **APK**: installed on Samsung Galaxy M51 (Android 13).
- **Raw ServerSocket HTTP server**: binds to `0.0.0.0:8443`, serves all endpoints correctly.
- **Health endpoint**: `GET /api/v1/health` — 200 OK with device info
- **Pairing flow**: `GET /api/v1/auth/pairing-code` → server returns code (doesn't regenerate if valid) → `POST /api/v1/auth/pair` → returns token + refreshToken
- **Authenticated endpoints**: `GET /api/v1/device/info`, `GET /api/v1/location/current`, etc. — Bearer token validation via HMAC-SHA256 (AndroidKeyStore)
- **Dashboard CLI**: `python3 artemis.py pair --code --save`, `info`, `location` all functional
- **Pairing code sync**: Phone fetches the server's actual pairing code (no more SecureRandom fallback)
- **Logging**: Extensive `android.util.Log` at every step (tags: ArtemisApp, Dashboard, ArtemisSvc, ArtemisServer).

### What's Still Rough
- **Service dies on nav**: `startForegroundService()` used but no `bindService()`. When user leaves app and returns, service state is unknown.
- **No boot receiver**: Server dies on phone restart. No `BOOT_COMPLETED` receiver.
- **No permanent pairing**: Tokens stored in memory-only `activeTokens` map. Restart = all pairings lost.
- **No multi-device**: PC can only manage one phone at a time.
- **CLI not web**: Dashboard is a Python CLI, not a browser UI.
- **Camera/Mic untested**: Endpoints exist but need runtime permissions, not fully verified.
- **Saved token fragile**: keyed by IP, breaks when phone IP changes.

## v2.0 Architecture — Multi-Device RAT with Web Dashboard

The goal is an infrastructure where:
- Many Android phones run the Artemis app (foreground service always alive)
- A desktop web dashboard discovers, pairs with, and controls all phones
- Heavy operations (camera, mic, screen) spawn on-demand foreground services, not 24/7

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Phone A (M51)│  │ Phone B (X)  │  │ Phone C (X)  │
│ Server :8443 │  │ Server :8443 │  │ Server :8443 │
│ ┌── ALWAYS ─┐│  │ ┌── ALWAYS ─┐│  │ ┌── ALWAYS ─┐│
│ │ Base Srv  ││  │ │ Base Srv  ││  │ │ Base Srv  ││
│ │ (location)││  │ │ (location)││  │ │ (location)││
│ └───────────┘│  │ └───────────┘│  │ └───────────┘│
│              │  │              │  │              │
│ ┌── ON DEMAND─┐│  │ ┌── ON DEMAND─┐│  │ ┌── ON DEMAND─┐│
│ │ Camera FGS ││  │ │ Camera FGS ││  │ │ Camera FGS ││
│ │ Mic FGS    ││  │ │ Mic FGS    ││  │ │ Mic FGS    ││
│ │ Screen FGS ││  │ │ Screen FGS ││  │ │ Screen FGS ││
│ └────────────┘│  │ └────────────┘│  │ └────────────┘│
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       │                 │                 │
       └─────────────────┼─────────────────┘
                         │ LAN / Tailscale
               ┌─────────▼─────────┐
               │ PC Web Dashboard  │
               │ Flask/FastAPI     │
               │ ┌───────────────┐ │
               │ │ Phone Map     │ │
               │ │ Phone Cards   │ │
               │ │ → Camera on   │ │
               │ │ → Mic on      │ │
               │ │ → Screen view │ │
               │ │ → Location    │ │
               │ │ → Recordings  │ │
               │ │ → File system │ │
               │ └───────────────┘ │
               └───────────────────┘
```

### v2.0 Milestones

#### Milestone A: Service Resiliency (HIGH)
- [ ] Add `BOOT_COMPLETED` BroadcastReceiver to auto-start service on phone reboot
- [ ] Add AlarmManager watchdog: periodic check (every 60s) that service is alive, restart if dead
- [ ] Add `bindService()` in MainActivity so service survives navigation
- [ ] Make server port configurable (avoid conflicts with multiple phones on same LAN)

#### Milestone B: Permanent Pairing (HIGH)
- [ ] Persist paired clients in SQLite on phone (`paired_clients` table)
- [ ] PC generates a permanent device identity on first launch (saved in `~/.config/artemis/id.json`)
- [ ] One-time pairing flow:
  1. PC sends its public identity to phone via pairing code
  2. Phone stores PC identity + issues persistent token
  3. PC stores phone fingerprint + token
  4. Future connections: PC presents its identity → phone recognizes it → auto-auth, no code
- [ ] Pairing code hidden behind "Show Code" button (not displayed by default)

#### Milestone C: Multi-Phone Dashboard (HIGH)
- [ ] Replace CLI with Flask web app serving on `http://0.0.0.0:5000`
- [ ] LAN discovery via UDP broadcast (phone announces itself, dashboard discovers)
- [ ] Dashboard shows all discovered phones as cards on a map
- [ ] Click a phone → see: location on map, recent activity, controls
- [ ] WebSocket for live updates (location stream, status changes)

#### Milestone D: On-Demand Spawning (MEDIUM)
- [ ] Base server stays lightweight: location polling + heartbeat + auth
- [ ] When dashboard requests camera → phone spawns `CameraForegroundService` (new process)
- [ ] When dashboard requests mic → phone spawns `MicForegroundService`
- [ ] When dashboard requests screen → phone spawns `ScreenCastService` (MediaProjection)
- [ ] Services stop when dashboard disconnects or after configurable timeout
- [ ] Add rate limiting per-client to prevent battery drain attacks

#### Milestone E: Dashboard Features (MEDIUM)
- [ ] Real-time location on map (Leaflet/MapLibre)
- [ ] Location history with timeline slider
- [ ] Camera live feed (photo capture at N fps or HLS stream)
- [ ] Microphone: start/stop recording + download
- [ ] Screen: screenshot + streaming
- [ ] File browser: list files, download/upload
- [ ] Call recordings: list, play, download
- [ ] Contacts: read and search
- [ ] SMS: read (if permission granted)
- [ ] Battery alerts, offline detection

### Files to Focus On

| File | Purpose | Priority |
|---|---|---|
| `.../server/SimpleHttpServer.kt` | Raw HTTP server — core of all communication | HIGH |
| `.../auth/AuthManager.kt` | Token gen/validation, pairing logic | HIGH |
| `.../service/ArtemisSentinelService.kt` | Foreground service lifecycle | HIGH |
| `.../ui/screens/DashboardScreen.kt` | Phone UI — pairing code display | MEDIUM |
| `.../receiver/BootReceiver.kt` | NEW: auto-start on boot | HIGH |
| `.../receiver/WatchdogAlarm.kt` | NEW: keep-alive watchdog | HIGH |
| `.../db/PairedClientsDb.kt` | NEW: SQLite for paired clients | HIGH |
| `dashboard/artemis.py` | Deprecate — replace with Flask web app | MEDIUM |
| `dashboard/web/` | NEW: Flask web dashboard | MEDIUM |

### Build Commands
```bash
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk
export GRADLE_OPTS="-Djava.version=21"
cd ~/Projects/Artemis/Artiest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.artemis
adb shell am start -n com.example.artemis/.MainActivity
```

### Logcat Filter for Debugging
```bash
adb logcat -s ArtemisApp:V Dashboard:V ArtemisSvc:V ArtemisServer:V
```

### Test Connection (from desktop)
```bash
python3 -c "
import socket, time
s = socket.socket(); s.settimeout(5)
s.connect(('<phone-ip>', 8443))
s.send(b'GET /api/v1/health HTTP/1.0\r\nHost: <phone-ip>\r\nConnection: close\r\n\r\n')
time.sleep(1); print(s.recv(4096).decode()); s.close()
"
```

### Key Learnings (don't repeat these mistakes)
1. **Netty on Android 13 Samsung**: `noKeySetOptimization=true` and `noUnsafe=true` prevent crashes but don't fix silent failure. Raw `ServerSocket` is the reliable choice.
2. **CIO engine**: Accepts TCP, never processes HTTP on Samsung Android 13.
3. **`kotlinx.serialization` and `Map<String, Any?>`**: `Json.encodeToString(dataMap)` throws because `Any` isn't `@Serializable`. Build JSON manually with `buildJsonObject()`.
4. **`Build.getSerial()`**: Throws `SecurityException` on Android 10+ without `READ_PHONE_STATE`. Always wrap in try-catch.
5. **LaunchedEffect execution order**: In Compose, `LaunchedEffect(Unit)` runs during first composition. If it fetches from a server that hasn't started yet, it'll fail silently. Start service first, THEN fetch.
6. **Pairing code must be server-authoritative**: Never let the UI generate its own code. The server is the source of truth.
7. **AGP 9.3.1**: Built-in Kotlin compilation. Do NOT apply `kotlin-android` Gradle plugin (conflicts with Gradle 9.5 `kotlin-dsl`).
