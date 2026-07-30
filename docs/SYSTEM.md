# System Architecture: Artemis Android Sentinel

## 1. High-Level Architecture

```
┌─────────────────────────────────────────┐
│         Desktop Dashboard Client        │
│  (Electron / Web / CLI)                 │
└──────────────┬──────────────────────────┘
               │ TLS 1.3 WebSocket / HTTPS
               │ LAN (192.168.x.x)
               ▼
┌────────────────────────────────────────────────────────────┐
│              Android Device (Artemis Agent)                │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │               Foreground Service                     │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │  │
│  │  │ HTTP/WSS │  │ Auth     │  │ Command Router   │   │  │
│  │  │ Server   │◄─┤ Manager  │◄─┤ (Feature        │   │  │
│  │  │ (Ktor)   │  │          │  │  Dispatcher)    │   │  │
│  │  └──────────┘  └──────────┘  └───────┬──────────┘   │  │
│  └──────────────────────────────────────┼───────────────┘  │
│                                         │                   │
│  ┌──────────────────────────────────────┼───────────────┐  │
│  │           Feature Modules             │               │  │
│  │                                       ▼               │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐       │  │
│  │  │ Location │  │ Camera   │  │ Microphone   │       │  │
│  │  │ Tracker  │  │ Module   │  │ Module       │       │  │
│  │  ├──────────┤  ├──────────┤  ├──────────────┤       │  │
│  │  │ Contacts │  │ SMS/MMS  │  │ Call         │       │  │
│  │  │ Module   │  │ Module   │  │ Recorder     │       │  │
│  │  ├──────────┤  ├──────────┤  ├──────────────┤       │  │
│  │  │ Screen   │  │ File     │  │ Device Info  │       │  │
│  │  │ Capture  │  │ Access   │  │ Module       │       │  │
│  │  └──────────┘  └──────────┘  └──────────────┘       │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Data Layer                               │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────┐  │  │
│  │  │ SQLCipher DB │  │ Encrypted    │  │ File     │  │  │
│  │  │ (Location,   │  │ File Store   │  │ Cache    │  │  │
│  │  │ Contacts,    │  │ (Recordings, │  │          │  │  │
│  │  │ Logs)        │  │  Photos)     │  │          │  │  │
│  │  └──────────────┘  └──────────────┘  └──────────┘  │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

## 2. Component Architecture

### 2.1 Network Layer — Ktor Server

**Choice: Ktor Server**
- Native Kotlin, coroutine-based, non-blocking I/O
- Supports HTTP/1.1, HTTP/2, WebSocket natively
- Tiny binary size (no full servlet container)
- TLS 1.3 built in
- Excellent with Android's battery optimization (event-driven)

**Server Endpoints:**
```
# REST Endpoints
POST   /api/v1/auth/pair         — Initial device pairing
POST   /api/v1/auth/token        — Token refresh
GET    /api/v1/device/info       — Device info
GET    /api/v1/location/current  — Get current location
GET    /api/v1/location/history  — Get location history (date range)
POST   /api/v1/camera/capture    — Take photo
GET    /api/v1/camera/capture/:id — Get captured photo
POST   /api/v1/mic/record        — Start audio recording
GET    /api/v1/mic/recordings    — List recordings
GET    /api/v1/mic/stream        — Real-time audio stream (WS upgrade)
POST   /api/v1/calls/records     — Start call recording
GET    /api/v1/calls/records     — List call recordings
GET    /api/v1/contacts          — List contacts
GET    /api/v1/sms               — List SMS messages
GET    /api/v1/files/*           — Browse/download files
POST   /api/v1/files/*           — Upload files
POST   /api/v1/screen/capture    — Take screenshot
GET    /api/v1/screen/stream     — Screen recording stream (WS)

# WebSocket Endpoints
/ws/v1/stream/camera             — Real-time camera feed
/ws/v1/stream/mic                — Real-time audio feed
/ws/v1/stream/screen             — Real-time screen recording
/ws/v1/events                    — Device event stream (heartbeat, alarms)
/ws/v1/control                   — Bidirectional remote control
```

### 2.2 Authentication Flow

```
┌──────────┐                    ┌──────────┐
│  Client  │                    │  Device  │
└────┬─────┘                    └────┬─────┘
     │                               │
     │─── Pairing Request ──────────►│
     │                               │── Generate pairing code
     │◄── Pairing Code Display ──────│ (shown on device screen)
     │                               │
     │─── Pairing Code + PSK ───────►│
     │                               │── Validate code
     │                               │── Generate auth token
     │◄── Auth Token (JWT) ──────────│
     │                               │
     │─── API Request + Token ──────►│
     │                               │── Validate token
     │◄── Response ──────────────────│
     │                               │
     │─── Token Refresh ────────────►│
     │◄── New Token ─────────────────│
```

**Token Structure:**
- JWT-based signed with device-specific secret
- Claims: device_id, client_id, scope (permission bitmask), issued_at, expires_at
- Default expiry: 24 hours
- Refresh tokens: 30-day validity, single-use

### 2.3 Foreground Service Design

```
ArtemisSentinelService : Service()
├── onCreate()
│   ├── Build notification channel
│   ├── Start Ktor server on configurable port
│   ├── Init feature modules
│   └── Start location scheduler
├── onStartCommand()
│   └── Return START_STICKY for auto-restart
├── onDestroy()
│   ├── Graceful server shutdown
│   ├── Save state
│   └── Self-restart via WorkManager (if unexpected)
└── Notification:
    ├── Title: "Artemis Sentinel Active"
    ├── Body: "{device_name} — {client_count} connected"
    └── Action: "Open Dashboard" (launches app UI)
```

### 2.4 Location Tracking Subsystem

```
LocationTracker
├── Config: interval=10min, fastest=5min, priority=PRIORITY_BALANCED_POWER_ACCURACY
├── Uses FusedLocationProviderClient
├── On each location update:
│   ├── Validate accuracy (< 100m required)
│   ├── Store in SQLCipher DB: {lat, lng, accuracy, timestamp, provider}
│   └── Prune records older than 30 days
├── On-demand: returns latest cached location immediately
└── Power saving:
    ├── Passive location when screen on + app active
    ├── Balanced when in background
    └── Throttle updates if accuracy requirements met
```

### 2.5 Camera Subsystem

```
CameraModule
├── init: enumerate cameras, build capability map
├── capturePhoto(cameraId): → File(JPEG)
│   ├── Open Camera2/CameraX session
│   ├── Capture single frame
│   ├── Save to encrypted store
│   └── Return capture ID + metadata
├── startStream(cameraId, ws): → WebSocket stream
│   ├── Surface → ImageReader → JPEG encoding → WS frames
│   └── Configurable: resolution, FPS, quality
└── Note: Some OEMs restrict background camera access
```

### 2.6 Data Storage Schema

```
Table: location_history
├── id: INTEGER PRIMARY KEY
├── latitude: REAL NOT NULL
├── longitude: REAL NOT NULL
├── accuracy: REAL
├── provider: TEXT (gps/network/passive)
├── timestamp: INTEGER NOT NULL (Unix ms)
└── INDEX(timestamp)

Table: captured_media
├── id: TEXT PRIMARY KEY (UUID)
├── type: TEXT (photo/audio/recording/screenshot)
├── file_path: TEXT (encrypted path)
├── file_size: INTEGER
├── mime_type: TEXT
├── metadata: TEXT (JSON blob with camera info, duration, etc.)
├── created_at: INTEGER
└── INDEX(type, created_at)

Table: contacts_snapshot
├── id: INTEGER PRIMARY KEY
├── contact_id: TEXT
├── name: TEXT
├── phone_numbers: TEXT (JSON array)
├── emails: TEXT (JSON array)
├── photo_uri: TEXT
├── raw_json: TEXT (full snapshot)
└── captured_at: INTEGER

Table: sms_messages
├── id: INTEGER PRIMARY KEY
├── thread_id: TEXT
├── address: TEXT
├── body: TEXT
├── date: INTEGER
├── type: INTEGER (inbox/sent/draft)
└── captured_at: INTEGER

Table: auth_clients
├── client_id: TEXT PRIMARY KEY
├── client_name: TEXT
├── token_hash: TEXT
├── token_expiry: INTEGER
├── refresh_token_hash: TEXT
├── permission_scope: INTEGER (bitmask)
├── last_seen: INTEGER
├── paired_at: INTEGER
└── is_active: BOOLEAN

Table: auth_pairing_codes
├── code: TEXT PRIMARY KEY (6-digit)
├── expires_at: INTEGER
└── used: BOOLEAN

Table: system_events
├── id: INTEGER PRIMARY KEY
├── event_type: TEXT (service_start/stop/permission_error/crash)
├── description: TEXT
├── severity: TEXT (info/warning/error)
└── timestamp: INTEGER
```

### 2.7 Security Architecture

```
┌─────────────────────────────┐
│   TLS 1.3 (Network Layer)   │
│   Self-signed CA on first   │
│   run, client validates     │
│   via pairing code          │
└─────────────┬───────────────┘
              │
┌─────────────▼───────────────┐
│   Authentication Layer       │
│   JWT tokens signed w/ HMAC │
│   Token rotation + refresh   │
└─────────────┬───────────────┘
              │
┌─────────────▼───────────────┐
│   Permission Layer           │
│   Bitmask-based scope per    │
│   client, enforced at        │
│   Command Router level       │
└─────────────┬───────────────┘
              │
┌─────────────▼───────────────┐
│   Encryption at Rest         │
│   SQLCipher (DB)             │
│   AES-256-GCM (media files)  │
│   Android Keystore (keys)   │
└─────────────────────────────┘
```

## 3. Technology Stack

### 3.1 App Layer
- **Language:** Kotlin 1.9+ (coroutines, flow, serialization)
- **UI (config/setup):** Jetpack Compose (minimal UI, mostly service-based)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Build:** Gradle with Kotlin DSL, version catalog

### 3.2 Server & Networking
- **Ktor Server** — HTTP/WebSocket server running on device
- **Ktor Client** — (for potential relay/update features)
- **OkHttp** — fallback HTTP client
- **kotlinx.serialization** — JSON/Protobuf serialization
- **Android NSD / JmDNS** — Service discovery

### 3.3 Features
- **Google Play Services / FusedLocationProvider** — Location
- **CameraX** — Camera operations (simplifies Camera2)
- **MediaProjection API** — Screen capture/recording
- **MediaRecorder** — Audio/call recording
- **ExoPlayer / Media3** — Audio playback (streaming)
- **WebRTC (Google's libjingle_peerconnection)** — Real-time streaming

### 3.4 Storage
- **SQLCipher (net.zetetic:android-database-sqlcipher)** — Encrypted database
- **EncryptedSharedPreferences** — Auth tokens, config
- **Android Keystore** — Key management
- **Okio** — Efficient file I/O

### 3.5 Background & Lifecycle
- **Foreground Service** — Core server runtime
- **WorkManager** — Scheduled tasks (maintenance, cleanup)
- **Lifecycle-aware components** — Proper resource management

### 3.6 Build & CI
- **Gradle version catalog** — Dependency management
- **Detekt / Ktlint** — Code quality
- **GitHub Actions** — CI/CD

## 4. Data Flow Examples

### 4.1 Client Requests Current Location
```
Client                          Device
  │                               │
  │── GET /api/v1/location/current│
  │   Authorization: Bearer JWT   │
  │                               │── Validate token
  │                               │── Fetch latest from location cache
  │                               │── If stale (>30s), request fresh GPS
  │◄── 200 OK ────────────────────│
  │   { "lat": 37.7749,          │
  │     "lng": -122.4194,        │
  │     "accuracy": 8.5,         │
  │     "timestamp": ... }       │
  │                               │
```

### 4.2 Client Streams Camera Feed
```
Client                          Device
  │                               │
  │── WS /ws/v1/stream/camera    │
  │   Auth token in first frame   │
  │                               │── Validate token
  │                               │── Open camera session
  │                               │── Start ImageReader loop
  │◄── Binary frame: JPEG ────────│
  │◄── Binary frame: JPEG ────────│   (Configurable FPS, quality)
  │◄── Binary frame: JPEG ────────│
  │                               │
  │── WS close ──────────────────►│── Release camera
  │                               │
```

### 4.3 Location History Harvest
```
Client                          Device
  │                               │
  │── GET /api/v1/location/history│
  │   ?from=2026-07-01           │
  │   &to=2026-07-30             │
  │   Authorization: Bearer JWT   │
  │                               │── Query SQLCipher DB
  │◄── 200 OK ────────────────────│
  │   { "points": [              │
  │     { "lat": ..., "lng": ...,│
  │       "timestamp": ... },    │
  │     ... (4320 entries max)   │
  │   ]}                         │
  │                               │
```
