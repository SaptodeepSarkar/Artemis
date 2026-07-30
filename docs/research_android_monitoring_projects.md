# Android Security Monitoring / RAT Research Report

**Generated:** July 30, 2026
**Purpose:** Research similar open-source Android security monitoring and RAT projects for the Artemis project — a legitimate device-owner fleet monitoring app over local networks.

---

## Summary of Findings

This research identified **three categories** of relevant projects:

1. **Legitimate Security Monitoring Tools** — Open-source apps for device owners to self-monitor
2. **Open-Source RAT Projects (Educational)** — Research/pen-testing RATs with working architectures
3. **Comprehensive RAT List** — The wishihab/Android-RATList catalog of 80+ known projects

---

## Category 1: Most Relevant — Self-Hosted Monitoring (Server-on-Device Pattern)

### 1. HTTP on Fire ⭐61
**GitHub:** https://github.com/zahidaz/HTTPOnFire
**License:** Apache-2.0 | **Language:** Kotlin (Ktor Server + Jetpack Compose)

**Features (25+ device APIs):**
- **Remote Control:** Flashlight, Volume, Vibrate, Find My Phone, Text-to-Speech, Clipboard R/W, Camera capture (front/back), Microphone live streaming, Push notifications, Launch/Stop apps
- **Device Info:** Battery level/health/temp, WiFi SSID/IP/signal, Device model/OS/memory/storage, GPS coordinates, Contacts list, Installed apps
- **Content Hosting:** File sharing, folder browsing, custom HTTP routes, redirects, QR codes
- **Developer Tools:** Swagger UI, OpenAPI spec, Echo endpoint, Proxy forwarding, Activity log

**Tech Stack:** Kotlin, Ktor Server (HTTP engine running inside Android app), Jetpack Compose, Material 3, Swagger

**Architecture:** Android app runs an embedded Ktor HTTP server on port 8080. No cloud dependency — operates entirely over local WiFi. The app IS the server; browsers are the client.

**Communication:** HTTP/REST (Ktor Server running on device). Mic streaming via HTTP. No WebSocket needed for most features.

**Authentication:** Not implemented (no-auth by design for local network use). No encryption.

**Key Architecture Pattern:** Embedded HTTP server in Android app — clean REST API pattern. The most directly relevant to Artemis.

---

### 2. Scrcpy ⭐146,679
**GitHub:** https://github.com/Genymobile/scrcpy
**License:** Apache-2.0 | **Language:** C (client), Java (Android server)

**Features:** Display and control Android device via USB or TCP/IP. Screen mirroring with low latency. File transfer, clipboard sync, recording.

**Tech Stack:** C (SDL2/FFmpeg client), Java (Android server daemon), ADB

**Architecture:** Starts a Java server on the Android device via ADB. Server captures screen frames via MediaProjection API and streams over a socket. Client decodes via FFmpeg and displays via SDL2.

**Communication:** Custom TCP socket (ADB forwarded port or direct TCP). H.264 video stream over socket.

**Authentication:** Relies on ADB authentication (RSA key pair). No built-in app-layer auth.

**Key Architecture Pattern:** Device screen capture via MediaProjection API + socket streaming. Shows how to get screen content from Android.

---

### 3. Android-RTSP-ScreenCaster ⭐18
**GitHub:** https://github.com/warren-bank/Android-RTSP-ScreenCaster
**License:** GPL-3.0 | **Language:** Java

**Features:** Serves an RTSP video stream to mirror the device screen. Runs as foreground service with notification.

**Tech Stack:** Java, libstreaming (fork of fyhertz/libstreaming), RTSP protocol

**Architecture:** Android foreground service running an RTSP server on port 6554. Uses MediaProjection API for screen capture. Streams H.264 via RTSP.

**Communication:** RTSP (Real-Time Streaming Protocol) for video.

**Authentication:** None.

**Key Architecture Pattern:** RTSP streaming from Android — useful for live screen/camera streaming use cases.

---

## Category 2: Open-Source RAT Projects (Educational/Security Research)

### 4. AhMyth ⭐5,238 (Original) / ⭐1,312 (Maintained Fork)
**GitHub (Original):** https://github.com/AhMyth/AhMyth-Android-RAT
**GitHub (Fork):** https://github.com/Morsmalleo/AhMyth
**License:** GPL-3.0 | **Language:** Smali (Android), JavaScript/Electron (Server)

**Features:** Full Android remote administration — Camera, Microphone, Location, Contacts, SMS, Call logs, File manager, App manager.

**Tech Stack:**
- **Server side:** Electron app (Node.js + Socket.IO + jQuery UI)
- **Client side:** Java Android app (compiled to Smali)

**Architecture:** The server runs as an Electron desktop app. It opens a Socket.IO listener on a configurable port. The Android client connects outbound via WebSocket to the server's IP:port. This is a **reverse-connect** pattern (client connects to server, not server to client).

**Communication:** WebSocket (Socket.IO). Messages use a custom event name convention:
- `x0000ca` — Camera
- `x0000fm` — File Manager
- `x0000sm` — SMS
- `x0000cl` — Call Logs
- `x0000cn` — Contacts
- `x0000mc` — Microphone
- `x0000lm` — Location

**Authentication:** None — any client can connect to the Socket.IO server.

**Key Architecture Pattern:** Electron desktop server + Socket.IO WebSocket. The client identifies itself via handshake query params (model, manufacturer, OS version, unique ID).

---

### 5. AndroRAT ⭐4,924
**GitHub:** https://github.com/karma9874/AndroRAT
**License:** MIT | **Language:** Java (Android) + Python (Server)

**Features:** Full persistent backdoor. Camera (photo/video), Audio recording, Call logs, SMS inbox/sent, GPS location, SIM details, IP/MAC, Clipboard, Shell access, File upload/download. Invisible icon, auto-start on boot.

**Tech Stack:**
- **Server side:** Python (socket server + command-line interface)
- **Client side:** Java Android app

**Architecture:** Custom TCP socket-based communication. Android client initiates TCP connection to Python server (reverse-connect). Server sends text commands; client responds with data. Commands are text-based (e.g., `takepic 0`, `getSMS inbox`, `startAudio`, `getLocation`).

**Communication:** Raw TCP sockets. Binary data for images/video over the same socket, delimited by markers.

**Authentication:** None — IP-based filtering only (configurable in source).

**Key Architecture Pattern:** Simple TCP socket-based command/response protocol. Uses Android Services for background persistence, JobScheduler for reconnection.

**Android Source Files (key):**
- `tcpConnection.java` — Main socket handler, command parser
- `functions.java` — Device info, clipboard, camera list, SIM details
- `config.java` — Configurable IP/port

---

### 6. Ghost Framework ⭐3,379
**GitHub:** https://github.com/EntySec/Ghost
**License:** MIT | **Language:** Python

**Features:** Android post-exploitation framework via ADB. Remote shell, device control.

**Tech Stack:** Python (CLI tool)

**Architecture:** Uses ADB (Android Debug Bridge) to connect to Android devices over USB or TCP/IP. Exploits the `adb` protocol to gain shell access.

**Communication:** ADB protocol (not a custom server). Requires ADB debugging enabled on device.

**Authentication:** ADB RSA key authentication.

**Key Architecture Pattern:** Not a persistent agent — requires device to already have ADB enabled. Good for understanding ADB-based approaches.

---

### 7. L3MON ⭐722
**GitHub:** https://github.com/efxtv/L3MON
**License:** MIT | **Language:** Node.js (Server) + Java (Android client, Smali)

**Features:** GPS Logging, Microphone Recording, Contacts, SMS (read/send), Call Logs, Installed Apps, Stub Permissions, Live Clipboard Logging, Live Notification Logging, WiFi Networks, File Explorer & Downloader, Command Queuing, Built-in APK Builder.

**Tech Stack:**
- **Server side:** Node.js + Express + Socket.IO + EJS + SQLite (via lowdb)
- **Client side:** Java Android (APK built via apktool)
- **Dashboard:** EJS templates + jQuery + AdminLTE-style UI

**Architecture:** Cloud-based architecture. Node.js server running on a VPS/cloud listens for Android client connections via Socket.IO. Admin accesses web dashboard (port 22533). Android client connects outbound via WebSocket.

**Communication:** WebSocket (Socket.IO) for real-time data. Event-based message protocol. HTTP for web admin dashboard.

**Authentication:** MD5 password hashing (maindb.json admin credentials). Cookie-based session token (`loginToken`). Admin login at `/login` endpoint.

**Key Architecture Pattern:** Full web dashboard (Express + EJS) + Socket.IO for device communication. Database-backed client management. Command queuing for offline devices.

---

### 8. XploitSPY ⭐1,269
**GitHub:** https://github.com/XploitWizer-Community/XploitSPY
**Language:** Node.js (Server) + Smali (Android)

**Features:** Similar feature set to L3MON (almost identical codebase — likely a fork/derivative).

**Tech Stack:** Node.js + Express + Socket.IO + EJS

**Architecture:** Same as L3MON. WebSocket (Socket.IO) based communication. Web dashboard for admin.

**Communication:** WebSocket (Socket.IO)

**Authentication:** Cookie/session-based admin login. MD5 password hashing.

---

### 9. N1nj4sec/Pupy ⭐9,001
**GitHub:** https://github.com/n1nj4sec/pupy
**License:** NOASSERTION | **Language:** Python (with C extensions)

**Features:** Cross-platform C2 (Windows, Linux, OSX, Android). Post-exploitation framework. Webcam snapshots (front/back), GPS tracking, Text-to-speech, Shell access.

**Tech Stack:** Python (C2 server), Various payloads including Android.

**Architecture:** Full C2 framework with multiple transport options (HTTP, HTTPS, TCP, etc.). Android payload connects back to C2 server.

**Communication:** Multiple options — HTTP, HTTPS, TCP, WebSocket, custom protocols.

**Authentication:** RSA key-based authentication for C2 operations.

---

### 10. DogeRAT ⭐1,972
**GitHub:** https://github.com/shivaya-dav/DogeRat
**Language:** Node.js (Server) + Kotlin (Android)

**Features:** Camera, Mic, SMS, Contacts, Clipboard, Keylogger, Location, SIM info, Vibrate, Notification reader/sender, WebView control, Toast messages, Installed apps, Auto-start on boot

**Communication:** Telegram Bot API (not direct WebSocket). The C2 channel is Telegram.
- **Tech Stack (Server side):** Node.js + Express + Socket.IO
- **Tech Stack (Android):** Kotlin

**Key Architecture Pattern:** Telegram-based C2 channel — no port forwarding needed. Interesting alternative pattern but limited for local-network use.

---

### 11. Android Spy ⭐14
**GitHub:** https://github.com/SergeyIvanovDevelop/Android-Spy
**License:** CC BY-NC-SA 3.0 | **Language:** Java

**Features:** Audio stream, Video stream (front/rear cameras), Geolocation data, Google Maps integration.

**Architecture:** Client-server (both Android apps). Sender (client) collects data and sends via socket to Receiver (server) app. IP address hardcoded in client source.

**Communication:** Custom TCP sockets.

**Authentication:** None (IP hardcoded in source).

---

## Category 3: Other Interesting/Relevant Projects

| Project | Stars | Features | Communication |
|---------|-------|----------|---------------|
| **optman/android-cam-rtsp** ⭐40 | Camera/mic capture → RTP stream | RTSP/RTP streaming |
| **harishrahangdale/Android_Phone_Monitoring_App** ⭐3 | Call logs, SMS, Call recordings | HTTP to remote server |
| **himanshkukreja/bridgelink** ⭐6 | Remote ADB via secure tunnel | ADB + tunnel |
| **dirname/SMS-Verficaiton-Code-Server** ⭐7 | SMS verification code monitoring | HTTP Web server |
| **tarxemo/sms-hacking-android-app** ⭐29 | SMS monitoring + sync | HTTP to remote server |
| **wishihab/WeDefend-Android** ⭐24 | RAT detection/protection | N/A (defensive) |
| **zahidaz/HTTPOnFire** ⭐61 | **Most relevant)** Local HTTP server | **HTTP/REST (Ktor)** |

---

## Architecture Pattern Analysis

### Pattern 1: Embedded HTTP Server (Best for Artemis)
**Example:** HTTPOnFire
- **Description:** Android app runs an embedded web server (Ktor, NanoHTTPD, etc.)
- **Pros:** No reverse-connect needed, standard REST API, works with any HTTP client
- **Cons:** Needs phone IP address; firewall/port issues
- **Best for:** Local network monitoring where the phone acts as server

### Pattern 2: Reverse-Connect WebSocket (Most Common in RATs)
**Examples:** AhMyth, L3MON, XploitSPY
- **Description:** Android client initiates outbound WebSocket connection to centralized server
- **Pros:** No port forwarding needed for incoming connections; works across NAT
- **Cons:** Requires running a server somewhere; more complex setup
- **Best for:** Remote monitoring across networks

### Pattern 3: Raw TCP Socket
**Examples:** AndroRAT, Android-Spy
- **Description:** Simple text-command protocol over raw TCP
- **Pros:** Lightweight, no dependencies, easy to implement
- **Cons:** No built-in framing, no HTTP semantics, harder to build web dashboards
- **Best for:** Minimal bandwidth/processing environments

### Pattern 4: ADB-Based
**Example:** Ghost, Scrcpy
- **Description:** Uses Android Debug Bridge protocol. ADB must already be enabled.
- **Pros:** Uses well-tested Android infrastructure
- **Cons:** Requires USB debugging or ADB-over-TCPIP enabled; user must grant permissions
- **Best for:** Developer tools, testing

### Pattern 5: Telegram Bot C2
**Example:** DogeRAT
- **Description:** Uses Telegram Bot API as the communication channel
- **Pros:** No port forwarding, works globally, free infrastructure
- **Cons:** Rate-limited; depends on third-party service; not local-network only
- **Best for:** Remote RAT where direct connection is impossible

---

## Authentication Approaches

| Method | Projects Using | Security Level |
|--------|---------------|----------------|
| **No auth** | AhMyth, AndroRAT, HTTPOnFire | None (relies on network isolation) |
| **MD5 password + cookie** | L3MON, XploitSPY | Low (MD5 is weak) |
| **ADB RSA key** | Ghost, Scrcpy | Medium (RSA key exchange) |
| **RSA key (C2 framework)** | Pupy | High |
| **Telegram Bot token** | DogeRAT | Medium (depends on Telegram security) |

---

## Recommended Architecture for Artemis

Based on this research, the strongest patterns to follow are:

1. **Primary Pattern: Embedded HTTP Server** (like HTTPOnFire) — Android app runs Ktor/NanoHTTPD, exposes REST API over local WiFi. This is the most natural for a device-owner monitoring app.

2. **Screen Capture via MediaProjection API** (like Scrcpy/RTSP-ScreenCaster) — Use Android's MediaProjection API for screen capture. Stream as H.264 over WebSocket or via MJPEG over HTTP.

3. **Camera/Mic Streaming via RTSP or WebSocket** — RTSP for video (Android-RTSP-ScreenCaster approach), raw PCM or Opus via WebSocket for audio.

4. **WebSocket for real-time bidirectional communication** — Socket.IO (L3MON/AhMyth pattern) or raw WebSocket for low-latency commands and streaming.

5. **Authentication: Simple token-based** — Generate a random auth token on app first launch, displayed to user. Client must present token in HTTP header. Optionally, support PIN-based pairing.

---

## Key Android APIs Required

| Feature | Android API |
|---------|-------------|
| Screen capture | MediaProjectionManager (API 21+) |
| Camera | Camera2 API (API 21+) / CameraX |
| Microphone | MediaRecorder / AudioRecord |
| Location | FusedLocationProviderClient |
| Contacts | ContentResolver (ContactsContract) |
| SMS | ContentResolver (SmsContract) |
| Call logs | ContentResolver (CallLog) |
| File system | java.io.File / MediaStore |
| Background service | Foreground Service (API 26+) / JobScheduler |
| Boot receiver | RECEIVE_BOOT_COMPLETED |

---

## Full Project Index

### Primary (Deeply Investigated)

| # | Project | Stars | Architecture | Comm Protocol | Auth |
|---|---------|-------|-------------|---------------|------|
| 1 | **HTTPOnFire** | ⭐61 | Embedded Ktor HTTP Server | HTTP/REST | None |
| 2 | **AhMyth** | ⭐5,238 | Electron + Socket.IO Server | WebSocket (Socket.IO) | None |
| 3 | **AndroRAT** | ⭐4,924 | Python TCP server + Android client | Raw TCP sockets | None |
| 4 | **L3MON** | ⭐722 | Node.js + Socket.IO + Web Dashboard | WebSocket (Socket.IO) | MD5 + Cookie |
| 5 | **XploitSPY** | ⭐1,269 | Node.js + Socket.IO + Web Dashboard | WebSocket (Socket.IO) | MD5 + Cookie |
| 6 | **Ghost** | ⭐3,379 | Python ADB framework | ADB protocol | ADB RSA |
| 7 | **Pupy** | ⭐9,001 | Python C2 framework (cross-platform) | HTTP/HTTPS/TCP | RSA keys |
| 8 | **DogeRAT** | ⭐1,972 | Node.js + Kotlin | Telegram Bot API | Telegram token |
| 9 | **Android Spy** | ⭐14 | Dual Android apps (client/server) | Raw TCP sockets | None |
| 10 | **Scrcpy** | ⭐146,679 | C client + Java Android server | TCP socket over ADB | ADB RSA |
| 11 | **RTSP-ScreenCaster** | ⭐18 | Java Android RTSP server | RTSP | None |

### Secondary (Cataloged)
- See full list at https://github.com/wishihab/Android-RATList — 80+ known Android RAT projects documented with features and permission requirements.

---

## Key Repositories for Reference Code

| Purpose | Repository | Key Files |
|---------|-----------|-----------|
| **HTTP server in Android** | [HTTPOnFire](https://github.com/zahidaz/HTTPOnFire) | `app/src/main/kotlin/.../server/` (Ktor routes) |
| **Socket.IO Android client** | [AhMyth](https://github.com/AhMyth/AhMyth-Android-RAT) | `AhMyth-Victim/app/src/main/java/ahmyth/mine/king/ahmyth/` |
| **TCP socket Android client** | [AndroRAT](https://github.com/karma9874/AndroRAT) | `tcpConnection.java`, `functions.java`, `config.java` |
| **Node.js Socket.IO server** | [L3MON](https://github.com/efxtv/L3MON) | `index.js`, `includes/clientManager.js` |
| **RTSP screen streaming** | [RTSP-ScreenCaster](https://github.com/warren-bank/Android-RTSP-ScreenCaster) | Android Studio project source |
| **Electron RAT server** | [AhMyth](https://github.com/AhMyth/AhMyth-Android-RAT) | `AhMyth-Server/app/main.js` |
| **Web dashboard (Express+EJS)** | [L3MON](https://github.com/efxtv/L3MON) | `assets/views/`, `includes/expressRoutes.js` |
| **Screen capture via MediaProjection** | [Scrcpy](https://github.com/Genymobile/scrcpy) | `server/src/main/java/.../ScreenEncoder.java` |
