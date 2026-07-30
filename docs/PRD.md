# Product Requirements Document: Artemis Android Sentinel

## 1. Overview

**Artemis Android Sentinel** is a self-hosted, LAN-based security monitoring application that runs as a background server on Android devices. Its purpose is to allow a device owner to remotely monitor their own fleet of Android devices over a local network for security auditing, device tracking, and compromise detection.

### Core Philosophy
- **Self-hosted & private** — all data stays on the local network
- **Authorized access only** — zero-trust authentication via token handshake
- **Low profile** — runs as a foreground service with clear user notification
- **Battery conscious** — designed for 24/7 background operation
- **Owner's own devices only** — this is NOT a remote access trojan for third-party devices

## 2. Target Users
- Device owners with multiple Android devices (phones, tablets)
- Security-conscious users who want to monitor their fleet
- IT administrators managing company-owned Android devices in a local network

## 3. Features

### 3.1 Network Server (Core)
- HTTP/WebSocket server running in a foreground service
- Listens on a configurable port (default: 8443)
- mDNS/NSD service discovery so clients can find devices on LAN
- TLS encryption for all communication
- Supports multiple concurrent authenticated clients
- Keep-alive and reconnection handling

### 3.2 Authentication & Authorization
- Token-based authentication (AES-256 encrypted tokens)
- First-use pairing (device shows a one-time pairing code)
- Client certificate or pre-shared key option
- Per-client permission scopes (optional: restrict what each client can access)
- Session management with token expiry and refresh

### 3.3 Location Tracking
- Periodic location capture every 10 minutes via Fused Location Provider
- 30-day rolling location history stored locally in encrypted database
- On-demand location query from authenticated clients
- Geofenced alerts (optional future)
- Battery-efficient using Passive Location Provider where possible

### 3.4 Camera Access
- On-demand photo capture (front and rear camera)
- Video streaming (WebRTC or MJPEG over WebSocket)
- Camera list enumeration
- Supports Camera2 API (Android 5+) and CameraX (Android 10+)

### 3.5 Microphone / Audio
- On-demand audio recording
- Real-time audio streaming (low-latency)
- Ambient sound level monitoring (optional)

### 3.6 Call Recording
- Record incoming and outgoing phone calls
- Store recordings as encrypted files on device
- List and retrieve call recordings on demand
- **Critical limitation (Android 10+):** `MediaRecorder.AudioSource.VOICE_CALL` is deprecated and returns silence on API 29+. The `CAPTURE_AUDIO_OUTPUT` permission is system/signature-only — NOT available to third-party apps. The only viable approach on non-rooted devices is **AccessibilityService-based call state detection + speakerphone mic capture**, which captures only the phone's speaker audio (degraded quality, one-sided).
- **Root option:** With root access, `tinymix` + custom audio routing can capture both sides via the modem's audio path.
- **Legal:** Call recording is legally restricted in many jurisdictions and requires consent from all parties. App must include prominent legal disclaimers and a user acknowledgment before enabling this feature.

### 3.7 Contacts & SMS Access
- Read and return contacts list (name, phone, email, photo URI)
- Read and return SMS/MMS messages
- Search contacts
- **Note:** Android 14+ restricts SMS/MMS access to default SMS app. Alternative: use Storage Access Framework or notification listener.

### 3.8 Screen Control & Capture
- Screen capture (screenshot) on demand via MediaProjection API
- Screen recording / streaming (H.264 video stream over WebSocket)
- Remote input injection via AccessibilityService (tap, swipe, type)
- **Note:** MediaProjection requires user consent dialog each session on Android 10+. Remote input requires AccessibilityService.

### 3.9 File System Access
- Browse directory structure
- Upload/download files to/from device
- Access app-specific directories, shared storage, and SD card
- Respects scoped storage (Android 10+) — uses MediaStore and SAF where needed

### 3.10 Device Information
- Device name, model, Android version, build info
- Battery level and charging status
- Network status (WiFi SSID, IP address, signal strength)
- Installed apps list
- Running processes list
- Storage usage

## 4. Non-Functional Requirements

### 4.1 Performance
- Server start time: < 5 seconds
- Location capture: < 500ms per poll
- Photo capture: < 2 seconds
- Camera stream latency: < 500ms (WebRTC)
- Battery impact: < 5% per day in idle mode
- Memory footprint: < 150MB baseline

### 4.2 Security
- All network traffic encrypted (TLS 1.3 minimum)
- Local data encrypted at rest (SQLCipher for DB, AES-256 for files)
- Authentication tokens stored in EncryptedSharedPreferences / Android Keystore
- No hardcoded secrets
- Automatic token rotation every 24 hours

### 4.3 Reliability
- Foreground service with persistent notification (survives app swipes on most OEMs)
- Auto-restart on crash (WorkManager)
- Graceful degradation if permissions not granted
- Network change handling (WiFi → mobile data → offline)

### 4.4 Privacy & Legal
- Clear user-visible notification that service is running
- All data stored on-device, never sent to cloud
- Legal disclaimers for call recording and accessibility features
- Opt-in permissions at first launch
- Option to disable specific feature groups

## 5. Constraints

### 5.1 Android Version Targets
- **Minimum:** Android 8.0 (API 26)
- **Target:** Android 14+ (API 34)
- **Primary testing:** Android 10-14

### 5.2 Permission Requirements

| Permission | Purpose | Android Version |
|---|---|---|
| FOREGROUND_SERVICE | Run server in background | 9+ |
| FOREGROUND_SERVICE_DATA_SYNC | Server service type | 14+ |
| FOREGROUND_SERVICE_MEDIA_PLAYBACK | Audio streaming | 14+ |
| FOREGROUND_SERVICE_CAMERA | Camera ops in bg | 14+ |
| POST_NOTIFICATIONS | Persistent notification | 13+ |
| ACCESS_FINE_LOCATION | GPS location | All |
| ACCESS_BACKGROUND_LOCATION | Periodic location | 10+ |
| CAMERA | Photo/video capture | All |
| RECORD_AUDIO | Microphone | All |
| READ_CONTACTS | Contacts access | All |
| READ_SMS | SMS access | All+ (or default SMS) |
| READ_CALL_LOG | Call log | All |
| CAPTURE_AUDIO_OUTPUT | Call recording | 10+ (root/accessibility) |
| FOREGROUND_SERVICE_SYSTEM_EXEMPTED | Battery exemption | 14+ |
| SYSTEM_ALERT_WINDOW | Overlay (MediaProjection prep) | All |
| REQUEST_INSTALL_PACKAGES | Self-update | All |
| ACCESS_NETWORK_STATE | Network detection | All |
| CHANGE_WIFI_STATE | WiFi management | All |
| ACCESS_WIFI_STATE | WiFi info | All |

### 5.3 OEM-Specific Challenges
- Huawei/Honor: aggressive background killing
- Xiaomi: autostart and power saver exemptions needed
- Samsung: deep sleep optimization on One UI 4+
- OPPO/Vivo: background restrictions
- Solution: guide user to battery optimization exclusion + autostart permission

## 6. Use Cases

### 6.1 Device Lost on Premises
1. User opens dashboard on laptop
2. Scans LAN for active Artemis agents
3. Connects to lost device
4. Requests current GPS location
5. Triggers camera snapshot to identify surroundings

### 6.2 Security Audit
1. User checks all fleet devices from dashboard
2. Reviews 30-day location history for anomalies
3. Checks call logs and contact changes
4. Reviews installed apps for unknown software

### 6.3 Compromise Detection
1. Monitor device for unexpected call activity
2. Check camera activation logs
3. Review SMS for phishing attempts
4. Extract forensic evidence (screenshots, call recordings)

## 7. Out of Scope (v1.0)
- Cloud relay / remote access over internet (LAN-only for v1)
- GPS geofencing alerts
- Remote wipe / factory reset
- Keylogging
- WhatsApp / Telegram message interception
- Root-based features (optional post-v1)

## 8. Success Metrics
- Server uptime > 99% (not killed by OS)
- Average battery drain < 3% per day
- Location capture success rate > 95%
- Client connection latency < 1 second on LAN
- Zero data leaks (all traffic encrypted)
