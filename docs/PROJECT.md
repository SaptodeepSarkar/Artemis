# Project: Artemis Android Sentinel

## Overview

**Artemis Android Sentinel** is a self-hosted LAN-based Android security monitoring agent. It runs as a foreground service on Android devices and exposes an authenticated HTTP/WebSocket API over the local network. Authorized clients (desktop dashboard, other devices) can request camera captures, microphone recordings, location history, contacts, SMS, call records, screen captures, and file system access.

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Server runtime | Ktor Server (not NanoHTTPD) | Kotlin-native, coroutine-based, WebSocket built-in, TLS 1.3 |
| Communication | HTTP REST + WebSocket | REST for request/response, WS for streaming & events |
| Authentication | JWT + pairing code | Stateless, standard, client-isolated |
| Encryption | TLS 1.3 (self-signed) + SQLCipher + AES-256-GCM | Defense in depth |
| Location tracking | Fused Location Provider | Battery-optimized, Google-supported |
| Camera | CameraX (fallback Camera2) | Simpler API, lifecycle-aware |
| Screen capture | MediaProjection API | Official Android capture API |
| Storage | SQLCipher + EncryptedSharedPreferences | Zero-knowledge to cloud |
| UI | Jetpack Compose (minimal) | Mostly service-based, UI for setup only |
| Service discovery | Android NSD + mDNS | Standard zero-config LAN discovery |
| Call recording | AccessibilityService API | Only viable API on Android 10+ without root |

## Repository Structure

```
artemis-android-sentinel/
├── app/
│   ├── src/main/
│   │   ├── java/com/artemis/sentinel/
│   │   │   ├── ArtemisApp.kt                    — Application class
│   │   │   ├── service/
│   │   │   │   ├── ArtemisSentinelService.kt     — Foreground service
│   │   │   │   └── ServerLifecycleObserver.kt    — Lifecycle aware server
│   │   │   ├── server/
│   │   │   │   ├── ArtemisServer.kt              — Ktor server init
│   │   │   │   ├── routing/
│   │   │   │   │   ├── AuthRoutes.kt             — /api/v1/auth/*
│   │   │   │   │   ├── DeviceRoutes.kt           — /api/v1/device/*
│   │   │   │   │   ├── LocationRoutes.kt         — /api/v1/location/*
│   │   │   │   │   ├── CameraRoutes.kt           — /api/v1/camera/*
│   │   │   │   │   ├── MicRoutes.kt              — /api/v1/mic/*
│   │   │   │   │   ├── CallRoutes.kt             — /api/v1/calls/*
│   │   │   │   │   ├── ContactsRoutes.kt         — /api/v1/contacts
│   │   │   │   │   ├── SmsRoutes.kt              — /api/v1/sms
│   │   │   │   │   ├── FileRoutes.kt             — /api/v1/files/*
│   │   │   │   │   └── ScreenRoutes.kt           — /api/v1/screen/*
│   │   │   │   ├── middleware/
│   │   │   │   │   ├── Authentication.kt         — JWT validation
│   │   │   │   │   ├── Authorization.kt          — Permission scope check
│   │   │   │   │   └── RateLimiter.kt            — Request throttling
│   │   │   │   ├── websocket/
│   │   │   │   │   ├── CameraStreamHandler.kt    — WS camera stream
│   │   │   │   │   ├── MicStreamHandler.kt       — WS audio stream
│   │   │   │   │   ├── ScreenStreamHandler.kt    — WS screen recording
│   │   │   │   │   ├── EventStreamHandler.kt     — WS event stream
│   │   │   │   │   └── ControlHandler.kt         — WS remote control
│   │   │   │   └── discovery/
│   │   │   │       └── NsdServicePublisher.kt    — mDNS/NSD registration
│   │   │   ├── feature/
│   │   │   │   ├── location/
│   │   │   │   │   ├── LocationTracker.kt        — Periodic location capture
│   │   │   │   │   └── LocationRepository.kt     — DB operations
│   │   │   │   ├── camera/
│   │   │   │   │   ├── CameraController.kt       — Camera capture/stream
│   │   │   │   │   └── CameraRepository.kt       — Photo storage
│   │   │   │   ├── mic/
│   │   │   │   │   ├── MicRecorder.kt            — Audio recording
│   │   │   │   │   └── MicRepository.kt          — Audio file ops
│   │   │   │   ├── calls/
│   │   │   │   │   ├── CallRecorder.kt           — Call recording via Accessibility
│   │   │   │   │   └── CallRepository.kt
│   │   │   │   ├── contacts/
│   │   │   │   │   └── ContactsProvider.kt       — Contacts ContentResolver
│   │   │   │   ├── sms/
│   │   │   │   │   └── SmsProvider.kt            — SMS ContentResolver
│   │   │   │   ├── screen/
│   │   │   │   │   ├── ScreenCapturer.kt         — Screenshot via MediaProjection
│   │   │   │   │   ├── ScreenStreamer.kt         — H.264 screen stream
│   │   │   │   │   └── RemoteControlService.kt   — Input injection via Accessibility
│   │   │   │   └── device/
│   │   │   │       └── DeviceInfoProvider.kt     — Device/battery/network info
│   │   │   ├── auth/
│   │   │   │   ├── AuthManager.kt                — Pairing, token lifecycle
│   │   │   │   └── TokenValidator.kt             — JWT parse/validate
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   │   ├── AppDatabase.kt            — SQLCipher DB definition
│   │   │   │   │   ├── dao/
│   │   │   │   │   │   ├── LocationDao.kt
│   │   │   │   │   │   ├── MediaDao.kt
│   │   │   │   │   │   ├── SmsDao.kt
│   │   │   │   │   │   ├── ContactsDao.kt
│   │   │   │   │   │   ├── AuthDao.kt
│   │   │   │   │   │   └── EventLogDao.kt
│   │   │   │   │   └── entity/
│   │   │   │   │       └── *.kt
│   │   │   │   ├── store/
│   │   │   │   │   └── EncryptedFileStore.kt     — AES-256-GCM file ops
│   │   │   │   └── preferences/
│   │   │   │       └── SecurePreferences.kt      — EncryptedSharedPreferences
│   │   │   ├── security/
│   │   │   │   ├── CryptoManager.kt              — Key generation, AES ops
│   │   │   │   ├── CertificateGenerator.kt       — Self-signed TLS cert
│   │   │   │   └── KeyStoreManager.kt            — Android Keystore wrapper
│   │   │   └── ui/
│   │   │       ├── MainActivity.kt               — Setup/config activity
│   │   │       ├── screens/
│   │   │       │   ├── DashboardScreen.kt        — Server status, controls
│   │   │       │   ├── PairingScreen.kt          — Show pairing code
│   │   │       │   ├── PermissionsScreen.kt      — Permission onboarding
│   │   │       │   ├── SettingsScreen.kt         — Config (port, features)
│   │   │       │   └── ClientListScreen.kt       — Authorized clients
│   │   │       └── theme/
│   │   │           └── Theme.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── dashboard/                                     — Desktop dashboard (future)
│   └── web/
├── gradle/
│   └── libs.versions.toml                         — Version catalog
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .github/
│   └── workflows/
│       ├── build.yml
│       ├── lint.yml
│       └── test.yml
├── docs/
│   ├── PRD.md
│   ├── SYSTEM.md
│   ├── PROJECT.md
│   ├── RESEARCH.md
│   ├── TASKS.md
│   ├── AGENTS.md
│   ├── MEMORY.md
│   └── android-network-server-protocol.md          — Network protocol design (1,353 lines)
├── AGENTS.md
└── README.md
```

## Development Phases

### Phase 1: Foundation (Week 1-2)
- Project scaffolding (Gradle, version catalog, module structure)
- Foreground service with persistent notification
- Basic Ktor server binding to port
- Self-signed TLS certificate generation
- NSD service discovery registration
- Permission handling framework
- Encrypted storage layer (SQLCipher + Keystore)

### Phase 2: Authentication & Device Info (Week 3)
- Pairing code generation and validation
- JWT token creation and verification
- Token refresh mechanism
- Client management (register, list, revoke)
- Device info endpoints
- Rate limiting middleware

### Phase 3: Location & Data Collection (Week 4)
- Location tracker with 10-min interval
- Location history storage (SQLCipher)
- Location query endpoints (current + history)
- 30-day rolling window cleanup
- Contacts and SMS data collection
- Device info battery/network endpoints

### Phase 4: Camera & Mic (Weeks 5-6)
- Camera discovery and capability enumeration
- On-demand photo capture
- Camera WebSocket streaming (MJPEG)
- Audio recording on demand
- Audio WebSocket streaming (PCM/WAV)
- Media file encrypted storage

### Phase 5: Screen Capture & Call Recording (Weeks 7-8)
- MediaProjection permission flow
- Screenshot capture
- Screen recording WebSocket stream (H.264)
- Remote input injection (AccessibilityService)
- Call recording via AccessibilityService
- Call recording storage and retrieval

### Phase 6: Polish & Hardening (Weeks 9-10)
- Background survival testing on major OEMs
- Battery optimization passes
- Memory leak testing
- Configuration screen (port, auto-start, features)
- Crash reporting and auto-restart
- Client-side CLI/tool for testing
- Comprehensive README and documentation

## Dependencies (version catalog)

```toml
[versions]
kotlin = "1.9.22"
ktor = "2.3.7"
coroutines = "1.7.3"
room = "2.6.1"            # replaced by SQLCipher raw
compose-bom = "2024.02.00"
camerax = "1.3.1"
webrtc = "1.0.32006"
sqlcipher = "4.5.6"
okio = "3.7.0"
ksp = "1.9.22-1.0.17"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
ktor-server-tls = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-auth-jwt = { module = "io.ktor:ktor-server-auth-jwt", version.ref = "ktor" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }

kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version = "1.6.2" }

camerax-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }

androidx-security-crypto = { module = "androidx.security:security-crypto", version = "1.1.0-alpha06" }

netty-tcnative = { module = "io.netty:netty-tcnative-boringssl-static", version = "2.0.61.Final" }

sqlcipher-android = { module = "net.zetetic:android-database-sqlcipher", version.ref = "sqlcipher" }
androidx-sqlite-ktx = { module = "androidx.sqlite:sqlite-ktx", version = "2.4.0" }

okio = { module = "com.squareup.okio:okio", version.ref = "okio" }

androidx-lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version = "2.7.0" }
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version = "2.9.0" }

# UI (minimal)
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-navigation = { module = "androidx.navigation:navigation-compose", version = "2.7.7" }
```

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Android 16 (API 35) 6-hour FGS timeout | High (future) | High | WorkManager restart every 5h; target SDK still 34 until mitigation tested |
| OEM kills background service | High | High | Guide users to battery exemption; WorkManager auto-restart; persistent notification |
| Android 14+ foreground service restrictions | Medium | High | Declare correct service types; request SYSTEM_EXEMPTED permission |
| Call recording blocked on Android 10+ | High | Medium | Use AccessibilityService; root alternative; clear legal disclaimers |
| Camera access blocked in background (OEM) | Medium | Medium | Graceful error; notify client; fallback to last captured |
| Network IP changes frequently | High | Medium | mDNS registration; heartbeat mechanism; client reconnection |
| Battery drain concerns | Medium | Medium | Throttle location updates; passive providers; adaptive polling |
| Legal exposure (call recording) | Medium | High | Prominent warnings; only record outgoing; user consent acknowledgment |
