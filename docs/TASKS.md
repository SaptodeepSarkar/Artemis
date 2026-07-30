# Tasks — Artemis Android Sentinel

## Phase 1: Foundation (Week 1-2)
Priority: HIGH — must complete before any feature work

### P1.1 Project Scaffolding
- [ ] Initialize Android project with Kotlin + Gradle + version catalog
- [ ] Set up module structure (app only for v1)
- [ ] Configure kotlinx.serialization, coroutines, and Ktor dependencies
- [ ] Set up Ktlint / Detekt code quality
- [ ] Create `.github/workflows/build.yml` CI pipeline
- [ ] Create README.md with build instructions

### P1.2 Foreground Service & Notification
- [ ] Implement `ArtemisSentinelService` extending `Service()`
- [ ] Create notification channel "Artemis Sentinel" (IMPORTANCE_LOW)
- [ ] Build persistent notification showing device name + client count
- [ ] Handle `START_STICKY` for auto-restart
- [ ] Handle `onDestroy()` — graceful shutdown + WorkManager restart

### P1.3 Ktor Server
- [ ] Implement `ArtemisServer.kt` — Ktor embedded server init
- [ ] Choose engine: Netty with netty-tcnative-boringssl-static for TLS
- [ ] Bind to configurable port (default 8443)
- [ ] Implement TLS 1.3 with self-signed certificate
- [ ] Health check endpoint: `GET /api/v1/health`
- [ ] JSON serialization with kotlinx.serialization
- [ ] Structured error responses (sealed class Success/Error)

### P1.4 NSD Service Discovery
- [ ] Register mDNS service type `_artemis._tcp`
- [ ] Publish device name + port via Android NSD API
- [ ] Handle network changes (WiFi disconnect/reconnect) — re-register
- [ ] Test with Bonjour browser on desktop

### P1.5 Permission Framework
- [ ] Request permissions at first launch with rationale screens
- [ ] Handle permission denial gracefully (disable affected features)
- [ ] Permission groups: Location, Camera, Mic, Contacts, SMS, Phone, Notifications
- [ ] Background location rationale (Android 10+ requires separate request)
- [ ] MediaProjection permission screen capture flow

### P1.6 Storage Foundation
- [ ] Initialize SQLCipher database with passphrase from Android Keystore
- [ ] Create all table schemas (location_history, captured_media, contacts_snapshot, sms_messages, auth_clients, auth_pairing_codes, system_events)
- [ ] Implement DAOs for all tables
- [ ] Implement `EncryptedFileStore.kt` — AES-256-GCM file encryption
- [ ] Implement `SecurePreferences.kt` — EncryptedSharedPreferences wrapper
- [ ] Implement `CryptoManager.kt` — key generation, encryption helpers
- [ ] Implement `KeyStoreManager.kt` — Android Keystore operations

## Phase 2: Authentication & Device Info (Week 3)
Priority: HIGH — required before any client can connect

### P2.1 Pairing System
- [ ] Implement pairing code generation (6-digit, 5-minute expiry)
- [ ] Pairing screen in Compose UI showing the code
- [ ] Pairing API endpoint: `POST /api/v1/auth/pair`
- [ ] Store paired clients in auth_clients table

### P2.2 JWT Authentication
- [ ] JWT token generation with HMAC-SHA256 signing
- [ ] JWT validation middleware in Ktor pipeline
- [ ] Token claims: device_id, client_id, scope bitmask, issued_at, expires_at
- [ ] Token refresh endpoint: `POST /api/v1/auth/token`
- [ ] Refresh token rotation (single-use, 30-day expiry)

### P2.3 Client Management
- [ ] List authorized clients: `GET /api/v1/auth/clients`
- [ ] Revoke client: `DELETE /api/v1/auth/clients/:id`
- [ ] Client management screen in Compose UI
- [ ] Permission scope per client (bitmask)

### P2.4 Device Info
- [ ] Device info endpoint: `GET /api/v1/device/info`
- [ ] Include: model, manufacturer, Android version, build, screen size
- [ ] Battery state (level, charging, health)
- [ ] Network state (WiFi SSID, IP, signal strength, mobile data status)
- [ ] Storage usage (internal + external)

### P2.5 Rate Limiting
- [ ] Per-client rate limiter (sliding window)
- [ ] Default: 60 requests/minute for REST, unlimited for WS streams
- [ ] Configurable in settings

## Phase 3: Location & Data Collection (Week 4)
Priority: HIGH — core data collection feature

### P3.1 Location Tracker
- [ ] LocationTracker using FusedLocationProviderClient
- [ ] Periodic capture every 10 minutes (WorkManager periodic task + FusedLocation callback)
- [ ] Configurable: update interval, fastest interval, priority
- [ ] Accuracy validation: reject locations with accuracy > 100m
- [ ] Provider-aware: GPS, Network, Passive

### P3.2 Location Storage
- [ ] Store location in location_history table
- [ ] Prune records older than 30 days (run on insert + periodic cleanup)
- [ ] Batch insert for efficiency

### P3.3 Location API Endpoints
- [ ] `GET /api/v1/location/current` — return latest cached location (fetch fresh if stale)
- [ ] `GET /api/v1/location/history?from=&to=` — return location history for date range
- [ ] Support pagination for large history requests
- [ ] Return GeoJSON format for map display on client

### P3.4 Contacts Collection
- [ ] `ContactsProvider.kt` — read contacts via ContentResolver
- [ ] Cache snapshot in contacts_snapshot table
- [ ] Support search: `GET /api/v1/contacts?q=`
- [ ] Return: name, phone numbers, emails, photo URI (not photo binary)

### P3.5 SMS Collection
- [ ] `SmsProvider.kt` — read SMS via ContentResolver
- [ ] Handle Android 14+ restrictions (default SMS app limitation)
- [ ] `GET /api/v1/sms?limit=&offset=`
- [ ] Support thread-based grouping
- [ ] Note: on Android 14+ without default SMS app role, this may fail

## Phase 4: Camera & Mic (Weeks 5-6)
Priority: MEDIUM — important for security monitoring

### P4.1 Camera Controller
- [ ] Enumerate cameras: `CameraController.getCameraList()`
- [ ] CameraX lifecycle binding (use ProcessLifecycleOwner for service)
- [ ] On-demand photo capture with configurable resolution
- [ ] Support front + rear cameras
- [ ] Store photos in encrypted file store

### P4.2 Camera API Endpoints
- [ ] `GET /api/v1/camera/list` — list available cameras + capabilities
- [ ] `POST /api/v1/camera/capture` — take photo with specified camera
- [ ] `GET /api/v1/camera/captures` — list previous captures
- [ ] `GET /api/v1/camera/captures/:id/file` — download capture file

### P4.3 Camera WebSocket Streaming
- [ ] `WS /ws/v1/stream/camera` — real-time camera feed
- [ ] MJPEG stream over WebSocket binary frames
- [ ] Configurable: resolution (VGA/HD/FHD), quality (1-100), FPS (5-30)
- [ ] Handle cleanup: release camera on WS close

### P4.4 Microphone Recording
- [ ] `MicRecorder.kt` — AudioRecord + MediaRecorder
- [ ] On-demand recording: start/stop/buffer
- [ ] Support PCM and AAC encoding
- [ ] Store recordings in encrypted file store

### P4.5 Microphone API Endpoints
- [ ] `POST /api/v1/mic/record/start` — start recording
- [ ] `POST /api/v1/mic/record/stop` — stop and save
- [ ] `GET /api/v1/mic/recordings` — list recordings
- [ ] `GET /api/v1/mic/recordings/:id/file` — download recording

### P4.6 Microphone WebSocket Streaming
- [ ] `WS /ws/v1/stream/mic` — real-time audio stream
- [ ] PCM 16-bit 44.1kHz mono audio frames
- [ ] Low-latency mode (minimize buffer)

## Phase 5: Screen Capture & Call Recording (Weeks 7-8)
Priority: MEDIUM — most complex features

### P5.1 Screen Capture
- [ ] MediaProjection permission request flow
- [ ] `ScreenCapturer.kt` — capture screenshot via MediaProjection
- [ ] Handle permission re-request (MediaProjection permission not persistent)
- [ ] Store screenshots in encrypted file store

### P5.2 Screen Streaming
- [ ] `ScreenStreamer.kt` — H.264 screen recording via MediaCodec
- [ ] `WS /ws/v1/stream/screen` — WebSocket stream with H.264 NAL units
- [ ] Configurable: resolution, bitrate, FPS
- [ ] Handle permission expiry (Android 10+ limitation)

### P5.3 Remote Input Injection
- [ ] AccessibilityService: `RemoteControlService.kt`
- [ ] Swipe/tap events forwarded from WebSocket
- [ ] `WS /ws/v1/control` — bidirectional control channel
- [ ] Security: require specific permission scope on client token
- [ ] Safety: inject only when device is unlocked

### P5.4 Call Recording
- [ ] AccessibilityService for call state detection
- [ ] MediaRecorder for audio capture (if API-level valid)
- [ ] For Android 10+: use AccessibilityService to detect call state, use AudioRecord if root, or rely on speakerphone mic capture
- [ ] Store recordings in encrypted file store
- [ ] Legal disclaimer UI on first use
- [ ] `POST /api/v1/calls/records/start` / `POST /api/v1/calls/records/stop`
- [ ] `GET /api/v1/calls/records` — list call recordings
- [ ] `GET /api/v1/calls/records/:id/file` — download call recording

## Phase 6: Polish & Hardening (Weeks 9-10)
Priority: MEDIUM — production readiness

### P6.1 Background Survival
- [ ] Test on: Pixel 6/7, Samsung S22/23, Xiaomi Redmi, OnePlus
- [ ] Add battery optimization exclusion guide per OEM
- [ ] WorkManager periodic health check (every 15 min)
- [ ] If server not running → restart it
- [ ] Monitor system events (ACTION_SHUTDOWN, battery low, etc.)

### P6.2 Battery Optimization
- [ ] Profile battery drain in idle mode
- [ ] Adaptive location polling (reduce frequency when device is stationary)
- [ ] Camera/mic streams: stop when no active WebSocket connections
- [ ] Reduce location updates when accuracy is stable

### P6.3 Memory & Stability
- [ ] Memory leak testing (leakcanary in debug)
- [ ] Thread pool sizing for Ktor server
- [ ] ImageReader buffer management (camera)
- [ ] Proper WebSocket cleanup on disconnect
- [ ] Crash reporting (Acra or self-hosted)

### P6.4 Configuration UI
- [ ] Settings screen: port, auto-start, notification toggle
- [ ] Feature toggle: enable/disable specific feature groups
- [ ] Connection status display
- [ ] Client list with last-seen timestamps
- [ ] Log viewer

### P6.5 Client Testing Tool
- [ ] CLI client in Python or Kotlin for testing
- [ ] Pairing flow test
- [ ] End-to-end: connect → authenticate → request location → receive
- [ ] End-to-end: camera stream for 60s → disconnect

## Phase 7: Documentation & Release (Week 11)
Priority: LOW — but essential for usability

### P7.1 Documentation
- [ ] Complete docs/AGENTS.md with working conventions
- [ ] API reference (OpenAPI 3.0 spec)
- [ ] Build from source guide
- [ ] OEM battery exemption guide
- [ ] Security architecture explanation

### P7.2 Legal
- [ ] Add LICENSE file
- [ ] Privacy notice (in-app)
- [ ] Call recording legal disclaimer
- [ ] AccessibilityService disclosure

### P7.3 Release
- [ ] Build signed APK/AAB
- [ ] Test install on clean device
- [ ] Verify all permissions needed are listed
- [ ] Create release on GitHub with changelog
