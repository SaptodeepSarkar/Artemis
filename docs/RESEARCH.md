# Research Report: Artemis Android Sentinel

## 1. Similar Open-Source Projects (Subagent 1 Findings)

### 1.1 Matching Architecture: HTTPOnFire
- **GitHub:** https://github.com/zahidaz/HTTPOnFire (61 stars, Apache-2.0)
- **Stack:** Kotlin, Ktor Server, Jetpack Compose — **identical to our proposed stack**
- **Features:** Android HTTP server with remote control panel via web UI
- **Auth:** Token-based pairing via app-generated codes
- **Why it matters:** This is the closest existing project to our architecture. validates Ktor-on-Android as a viable approach. The key difference: our app adds persistent foreground service, encrypted storage, location history, camera/mic streaming, call recording — much deeper than HTTPOnFire's web-based control.

### 1.2 Educational RAT Projects (Architecture Reference)

| Project | Stars | License | Language | Server | Features |
|---|---|---|---|---|---|
| **AhMyth** | 5.2k | GPL-3.0 | Smali/Java+Electron | Socket.IO | Camera, mic, location, contacts, SMS, file mgr, call logs |
| **AndroRAT** | 4.9k | MIT | Java+Python | TCP Sockets | Reverse shell, camera, mic, location, SMS, file access |
| **Ghost (EntySec)** | 3.3k | MIT | Python | ADB-based | Requires ADB, post-exploitation framework |
| **XploitSPY** | 1.2k | NOASSERTION | Smali | HTTP | Monitoring tool with dashboard |
| **L3MON** | 722 | MIT | Smali+Node.js | Socket.IO | Remote admin, HTTP server on device |

**Key Takeaways from these projects:**
1. **Socket.IO is popular for RATs** but requires Node.js server. Ktor + native WS is cleaner for an all-Kotlin stack.
2. **Most use old Java/Smali** — our Kotlin-first approach is a modernization.
3. **None use encryption** (all plaintext) — our TLS 1.3 requirement is a differentiator.
4. **All require payload delivery** (the malicious RAT vector). Our app is installed by the owner — different threat model.
5. **None do persistent location history** (30-day rollups) — that's unique to our design.

### 1.3 Legitimate Remote Access Tools

| Project | Features | Auth | Network | Limitation |
|---|---|---|---|---|
| **Scrcpy** (110k★) | Screen mirroring, control | ADB trust | USB/WiFi ADB | Requires ADB enabled |
| **AirDroid** | File, SMS, camera, screen | Cloud account | Internet relay | Increasingly commercial, closed |
| **TeamViewer Host** | Remote control, file | Partner ID | Internet relay | Closed, requires relay |
| **KDE Connect** (2.5k★) | File, clipboard, remote input, notify | Pairing code (6-digit) | LAN custom TCP | No camera/mic access, no location history |

**Key Takeaways:**
1. **KDE Connect's pairing flow** is our template: mDNS discovery → 6-digit code → TLS handshake → token session.
2. **Scrcpy's MediaCodec H.264 pipeline** is what we use for screen streaming.
3. **No existing tool combines all features** (camera + mic + location history + contacts + SMS + screen + call recording) in a self-hosted LAN package. This is a genuine gap.

## 2. Network Protocol Design (Subagent 3 Findings)

Full 1,353-line protocol design document at: `docs/android-network-server-protocol.md`

### 2.1 Protocol Selection

**Winner: WebSocket (primary) + HTTP REST (auxiliary)**

| Protocol | Use Case | Rationale |
|---|---|---|
| **WebSocket** | Real-time streams (camera, audio, screen, events) | Persistent connection, low per-frame overhead, bidirectional, battery-efficient (single TCP conn) |
| **HTTP REST** | CRUD operations (history queries, device info, settings) | Simple, cacheable, standard tooling |
| **gRPC** | Rejected | Heavy server library on Android (~7MB), HTTP/2 advantage marginal on LAN |
| **MQTT** | Rejected | Needs broker on-device (overhead), pub/sub model less natural for streaming to specific clients |
| **Custom TCP** | Rejected | Too complex — would need to reimplement framing, backpressure, TLS, connection management |

### 2.2 Device Discovery (Recommended: 3-tier)

| Tier | Method | Protocol | Battery Impact |
|---|---|---|---|
| **Primary** | mDNS/DNS-SD via Android NsdManager | Multicast DNS | Low (event-driven) |
| **Fallback** | UDP broadcast to 255.255.255.255:9090 | UDP | Very low (one-shot per request) |
| **Manual** | QR code encoding `artemis://ip:port?key=hash` | Visual | None |

**Decision tree:** mDNS (10s timeout) → UDP broadcast (5s timeout) → Try saved IPs → Show QR code

### 2.3 Authentication (3-tier stack)

| Layer | Mechanism | Purpose |
|---|---|---|
| **Transport** | TLS 1.3 (self-signed cert) | Encryption + server identity |
| **Identity** | Challenge-response (asymmetric) | Client proves possession of paired key |
| **Session** | JWT bearer tokens (24h expiry, refresh) | Authorization for subsequent requests |

**Recommended cipher:** `TLS_AES_128_GCM_SHA256` (HW-accelerated on modern ARM) with `TLS_CHACHA20_POLY1305_SHA256` fallback.

### 2.4 Data Serialization

| Layer | Format | Why |
|---|---|---|
| **REST endpoints** | JSON (kotlinx.serialization) | Human-readable, debuggable, standard |
| **WebSocket streams** | Protocol Buffers (protobuf-javalite) | Compact binary, fixed schema, low CPU overhead |
| **Camera/audio frames** | Raw binary over WS | No serialization overhead for already-encoded media |
| **Frame compression** | JPEG (camera), WebP (screen), Opus (audio) | Hardware-accelerated codecs |

### 2.5 Android Server Library Comparison: Winner = Ktor + Netty

| Library | Size | WS | REST | TLS | Battery | Verdict |
|---|---|---|---|---|---|---|
| **Ktor + Netty** | +2.5 MB | ✅ Built-in | ✅ Built-in | ✅ Auto | ★★★★☆ | **RECOMMENDED** |
| **NanoHTTPD** | +100 KB | ⚠️ Addon | ✅ Native | ⚠️ Manual | ★★★★★ | OK for REST-only |
| **Netty (raw)** | +5-15 MB | ✅ | ✅ | ✅ | ★★★☆☆ | Overkill |
| **Java-WebSocket** | +100 KB | ✅ Native | ❌ | ⚠️ | ★★★★☆ | WS-only, no REST |

### 2.6 Keep-Alive & Reconnection

- **WebSocket Ping/Pong:** Every 15s, close if no Pong in 10s
- **Exponential backoff:** 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped) with ±25% jitter
- **Network changes:** `ConnectivityManager.NetworkCallback` → re-discover via mDNS → reconnect
- **Graceful shutdown:** Server sends `{"type":"shutdown"}` 2s before going down
- **Session recovery:** On reconnect, server sends last sequence number per stream

### 2.7 Concurrent Client Architecture

- Max 10 concurrent WebSocket clients (battery budget)
- Per-client subscription system (camera, audio, location, screenshot)
- Backpressure via bounded channel (32 frames, `DROP_OLDEST`)
- Frame distribution: hybrid broadcast + per-client adaptation

## 3. Android Tech Stack Research (Subagent 2 Findings)

### 3.1 Complete Library/API Stack

| Feature | API/Library | Permissions | Min API | Notes |
|---|---|---|---|---|
| **HTTP/WS Server** | Ktor Server (Netty engine) | FOREGROUND_SERVICE | 26 (8.0) | FGS type required on 34+ |
| **Foreground Service** | `Service()` + START_STICKY | POST_NOTIFICATIONS (33+) | 26 | Self-restart via WorkManager |
| **Location** | FusedLocationProviderClient | ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION | 29 (10+) for bg | Passive provider when idle |
| **Camera** | CameraX | CAMERA | 21 | Lifecycle-aware, device compat |
| **Audio Record** | AudioRecord / MediaRecorder | RECORD_AUDIO | 16 | Opus encoding preferred |
| **Screen Capture** | MediaProjection + VirtualDisplay | None (user consent) | 21 (29+ for consent dialog) | Requires user consent each session on 10+ |
| **Screen Stream** | MediaCodec (H.264) + VirtualDisplay | None | 21 | Hardware encoding |
| **Contacts** | ContentResolver + ContactsContract | READ_CONTACTS | 1 | No changes in Android 14 |
| **SMS** | ContentResolver + Telephony.Sms | READ_SMS | 1 | Restricted on 14+ (default SMS app) |
| **Call Recording** | AccessibilityService + MediaRecorder | CAPTURE_AUDIO_OUTPUT, READ_PHONE_STATE | 29 (10+) | Blocked on 10+ without root |
| **Encrypted DB** | SQLCipher (net.zetetic) | None | 14 | AES-256 |
| **Encrypted prefs** | EncryptedSharedPreferences | None | 23 | Keys in Android Keystore |
| **Service Discovery** | NsdManager (built-in) | ACCESS_NETWORK_STATE | 16 | mDNS/DNS-SD |
| **Remote Input** | AccessibilityService | BIND_ACCESSIBILITY_SERVICE | 18 | User must enable in Settings |

### 3.2 Critical Android Version Restrictions

| Android Version | Key Restriction | Impact on Our App |
|---|---|---|
| **8.0 (API 26)** | Background service limits | Must use Foreground Service with notification |
| **9.0 (API 28)** | FGS notification must show | Notification is always visible (design choice) |
| **10.0 (API 29)** | Scoped storage, background location dialog, cannot record calls via CAPTURE_AUDIO_OUTPUT | Need `ACCESS_BACKGROUND_LOCATION` + settings intent; call recording falls back to AccessibilityService or root |
| **13.0 (API 33)** | POST_NOTIFICATIONS permission | Must request runtime permission for FGS notification |
| **14.0 (API 34)** | Foreground service types required | Must declare `foregroundServiceType="dataSync"`, `camera`, `microphone`, `mediaProjection` etc. |

### 3.3 Call Recording Reality (Android 10+)

**The most restricted feature.** Google intentionally blocked `CAPTURE_AUDIO_OUTPUT` in Android 10. Options:
1. **Root:** Use `su` + `tinymix` + custom audio routing (most reliable, requires root)
2. **AccessibilityService:** Detect call state changes, capture speaker output via mic (poor quality)
3. **Speakerphone mode + audio_record:** Enable speaker in call, record via mic (decent quality, works on all devices)
4. **VoIP apps:** Can capture via NotificationListener (partial)

**Our approach:** Use AccessibilityService for call detection, offer "record via speaker" mode, document limitations clearly. Legal disclaimer required.

## 4. Battery Optimization Research

### 4.1 Projected Battery Impact

| Component | Idle (no clients) | 1 client, periodic | Camera streaming |
|---|---|---|---|
| Ktor server (listening) | < 0.5% / day | < 1% / day | < 2% / day |
| Location (10-min interval) | ~1% / day | — | — |
| Camera stream (15 fps) | — | — | ~5-8% / hour |
| Audio stream | — | ~2% / hour | — |
| **Total idle** | **~2-3% / day** | **~3-5% / day** | **Streaming dominates** |

Comparison: KDE Connect ~2-3%/day, AirDroid ~4-5%/day. Our estimate is competitive.

### 4.2 Key Battery Strategies
1. **Adaptive location:** Use ActivityRecognition to detect "still" state → reduce to 1/hour
2. **Ktor NIO threads:** No polling threads; event-driven
3. **Camera closed when not streaming:** Keep camera closed by default, open on-demand
4. **Backpressure on streams:** Drop frames for slow clients instead of blocking
5. **WorkManager for periodic tasks:** Not AlarmManager (respects Doze mode)

## 5. Architecture Pattern Classification

Subagent 1 classified all discovered projects into **5 architecture patterns**:

| Pattern | Projects Using | Description | Best For |
|---|---|---|---|
| **Embedded HTTP Server** | HTTPOnFire, (ours) | Android runs Ktor/NanoHTTPD, exposes REST API over LAN | **OUR PATTERN** — phone IS the server |
| **Reverse-Connect WebSocket** | AhMyth, L3MON, XploitSPY | Android client initiates outbound WS to centralized server | Remote access across NAT |
| **Raw TCP Socket** | AndroRAT, Android-Spy | Lightweight text-command protocol over raw TCP | Minimal bandwidth env |
| **ADB-Based** | Ghost, Scrcpy | Uses Android Debug Bridge (ADB enabled required) | Developer tools |
| **Telegram Bot C2** | DogeRAT | Telegram Bot API as communication channel | Works globally, no ports |

## 6. Authentication Approaches Across Projects

| Method | Projects | Security |
|---|---|---|
| No auth | AhMyth, AndroRAT, HTTPOnFire | None (local network isolation relied upon) |
| MD5 + cookie | L3MON, XploitSPY | Low |
| ADB RSA key | Ghost, Scrcpy | Medium |
| RSA key (C2 framework) | Pupy | High |
| Telegram Bot token | DogeRAT | Medium |

**Our approach is stronger than all of them:** TLS 1.3 + challenge-response asymmetric auth + JWT tokens layered.

## 7. References and Key Code Repositories

| Purpose | Repository | Key Files to Study |
|---|---|---|
| **HTTP server in Android** | [HTTPOnFire](https://github.com/zahidaz/HTTPOnFire) | Ktor routes in `app/.../server/` |
| **Socket.IO Android client** | [AhMyth](https://github.com/AhMyth/AhMyth-Android-RAT) | `AhMyth-Victim/app/.../ahmyth/` |
| **TCP socket Android client** | [AndroRAT](https://github.com/karma9874/AndroRAT) | `tcpConnection.java`, `functions.java` |
| **Node.js Socket.IO server** | [L3MON](https://github.com/efxtv/L3MON) | `index.js`, `includes/clientManager.js` |
| **RTSP screen streaming** | [RTSP-ScreenCaster](https://github.com/warren-bank/Android-RTSP-ScreenCaster) | Android Studio project source |
| **Screen capture via MediaProjection** | [Scrcpy](https://github.com/Genymobile/scrcpy) | `ScreenEncoder.java` |
| **Full RAT catalog** | [Android-RATList](https://github.com/wishihab/Android-RATList) | 80+ projects catalogued |

- Full protocol design: [android-network-server-protocol.md](android-network-server-protocol.md) (1,353 lines)
- Full project analysis: [research_android_monitoring_projects.md](research_android_monitoring_projects.md) (364 lines)
- KDE Connect protocol docs: KDE's pairing and LAN discovery flow
- Android Developer Docs: Foreground Services, CameraX, MediaProjection, Fused Location Provider
- OWASP Mobile Security Testing Guide (MSTG)
- SQLCipher for Android: net.zetetic:android-database-sqlcipher
- Ktor Server docs: `ktor-server-netty`, `ktor-server-websockets`, JWT auth plugin
