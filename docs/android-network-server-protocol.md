# Android Local Network Server — Communication Protocol Design

> **Project:** Artemis — Android device as a local network server  
> **Date:** 2026-07-30  
> **Status:** Research & Design

---

## Table of Contents

1. [Protocol Selection](#1-protocol-selection)
2. [Device Discovery](#2-device-discovery)
3. [Authentication](#3-authentication)
4. [Encryption](#4-encryption)
5. [Data Serialization](#5-data-serialization)
6. [Keep-Alive & Reconnection](#6-keep-alive--reconnection)
7. [Concurrent Clients](#7-concurrent-clients)
8. [Android Library Comparison](#8-android-library-comparison)
9. [Recommended Architecture](#9-recommended-architecture)
10. [Implementation Roadmap](#10-implementation-roadmap)

---

## 1. Protocol Selection

### Candidates Evaluated

| Protocol | Transport | Best For | Android Suitability |
|---|---|---|---|
| **WebSocket** | TCP (persistent) | Bidirectional real-time, low-latency | ★★★★★ |
| **HTTP REST** | TCP (short-lived) | CRUD, request-response only | ★★★☆☆ |
| **gRPC** | HTTP/2 (bidirectional streams) | High-performance RPC, streaming | ★★★★☆ |
| **MQTT** | TCP (persistent pub/sub) | IoT sensor data, low bandwidth | ★★★★☆ |
| **Custom TCP** | Raw TCP | Maximum control, minimal overhead | ★★☆☆☆ |

### Recommendation: **WebSocket (primary) + HTTP REST (fallback/auxiliary)**

**Why WebSocket wins for this use case:**

1. **Bidirectional real-time** — The server pushes camera frames, audio chunks, and screenshots to clients without polling. WebSocket gives full-duplex over a single TCP connection.
2. **Low overhead** — After the initial HTTP upgrade handshake (~150 bytes), frames have only 2–6 bytes of framing overhead vs. HTTP's 200–800 byte headers per request.
3. **Battery-friendly** — A single persistent TCP connection uses far less radio energy than repeated HTTP connections. The Android WiFi radio enters a low-power state between keep-alive pings, whereas HTTP REST requires waking the radio for each separate request.
4. **Proven on Android** — OkHttp (the de facto standard HTTP client on Android) has first-class WebSocket support. Ktor and NanoHTTPD also support it.
5. **Graceful degradation** — Clients that can't do WebSocket (e.g., a `curl`-based health check) can fall back to a simple HTTP REST endpoint for status queries.

**Where gRPC nearly fits:**

gRPC's bidirectional streaming, Protobuf-native serialization, and HTTP/2 multiplexing make it a strong contender. However:
- gRPC on Android (server-side) requires a full gRPC server or at minimum a gRPC-reflection-aware HTTP/2 stack. The standard gRPC-Java server is heavy (~7 MB) and brings significant GC pressure.
- The `grpc-android` library has limited server-side support (it targets client-side use).
- HTTP/2 multiplexing benefits are marginal on a LAN (sub-millisecond latency, no head-of-line blocking problem).
- gRPC's keep-alive mechanism (HTTP/2 PING frames) works well but lacks browser support—important if one client is a web dashboard.

**Where MQTT nearly fits:**

MQTT is excellent for IoT sensor data and its QoS levels (0, 1, 2) handle message delivery guarantees elegantly. But:
- MQTT typically requires a broker (Mosquitto, EMQX). Running a broker on-device is an extra process, extra memory, and extra battery drain.
- MQTT's pub/sub model is less natural for streaming camera frames to a specific client dashboard than a direct connection.
- Without a broker, MQTT-SN or direct client-server MQTT is possible but loses most of MQTT's value.
- Binary payloads (camera frames) are fine in MQTT, but large messages (>256 KB) require fragmentation handling.

**Why not custom TCP:**

Writing a custom protocol on raw TCP sockets gives maximum control but enormous complexity: you'd need to reimplement framing, keep-alive, backpressure, message boundaries, encryption via TLS, connection pooling, and client management. Every bug is a security or stability issue. Netty on Android is possible but adds a 15 MB dependency. Don't do this unless you have a specific performance requirement that proven protocols can't meet.

### Dual-Protocol Architecture

```
┌─────────────────────────────────────┐
│           Android Server            │
│                                     │
│  ┌──────────────────────────────┐  │
│  │     Ktor Engine (Netty)      │  │
│  │                              │  │
│  │  /ws      → WebSocket       │  │
│  │  /api/v1/ → HTTP REST       │  │
│  │  /health  → HTTP (no auth)  │  │
│  └──────────────────────────────┘  │
└─────────────────────────────────────┘
```

- **Primary channel:** WebSocket (`ws://device-ip:8080/ws`) — used for all real-time data: camera frames, audio chunks, screenshots, device location updates, commands from dashboard.
- **Auxiliary channel:** HTTP REST (`http://device-ip:8080/api/v1/...`) — used for infrequent operations: fetching device info, fetching recorded history, changing settings, pairing new clients.
- **Health endpoint:** HTTP GET `/health` — no authentication, returns `{"status":"ok","version":"1.0","clients":2}` for network scanners and load balancers.

---

## 2. Device Discovery on LAN

Three mechanisms, each with different trade-offs. **Use all three in parallel** for maximum reliability.

### 2.1 mDNS/DNS-SD (Recommended Primary)

| Aspect | Detail |
|---|---|
| **How it works** | Device advertises `_artemis._tcp.local.` service via mDNS multicast. Clients query the `.local` domain. |
| **Android API** | `NsdManager` (Network Service Discovery) — first-class Android support since API 16. |
| **Library** | `android.net.nsd.NsdManager` standard, or JmDNS (more configurable). |
| **Pros** | Standard protocol; works with macOS (Bonjour), Linux (Avahi), Windows 10+; zero-config; built into Android. |
| **Cons** | Can be unreliable on some WiFi routers that block multicast; may take 5–15 seconds for discovery; device must register service. |
| **Battery cost** | Low — NsdManager uses multicast, doesn't require polling. |

**Example service registration:**

```kotlin
// Android — NsdManager
val serviceInfo = NsdServiceInfo().apply {
    serviceName = "Artemis-${deviceId}"
    serviceType = "_artemis._tcp."
    port = 8080
    setAttribute("version", "1.0")
    setAttribute("device", Build.MODEL)
}

nsdManager.registerService(
    serviceInfo,
    NsdManager.PROTOCOL_DNS_SD,
    registrationListener
)
```

### 2.2 UDP Broadcast (Fallback / Direct Discovery)

When mDNS fails (multicast blocked), clients can send a UDP broadcast to `255.255.255.255:9090` and the server responds.

```
CLIENT                          SERVER
  │                               │
  │  UDP broadcast to 255.255.255.255:9090  │
  │  {"type":"discover"}         │
  │ ───────────────────────────> │
  │                               │
  │  UDP unicast response         │
  │  {"type":"announce",          │
  │   "ip":"192.168.1.42",        │
  │   "port":8080,                │
  │   "device":"Pixel 8",         │
  │   "version":"1.0"}            │
  │ <─────────────────────────── │
```

**Implementation notes:**
- Use a `DatagramSocket` bound to a fixed discovery port (e.g., 9090).
- Keep response payload small (< 512 bytes to avoid IP fragmentation).
- Rate-limit responses (max 1 per client per 5 seconds) to avoid broadcast storms.
- Prefix with a magic byte (`0x41` for 'A') so non-Artemis listeners can quickly reject.

### 2.3 DNS-Based Discovery (Fallback)

For clients that know the network's DNS server, or for enterprise environments:
- Device registers a hostname like `artemis-<device-id>.local` via DHCP hostname option.
- Clients can try to resolve `artemis` or iterate `artemis-1`, `artemis-2`, etc.

### 2.4 QR Code / Manual Pairing (Bootstrap)

When zero-config discovery fails entirely, provide a fallback:
- Device shows a QR code on screen encoding: `artemis://<ip>:<port>?key=<publicKeyHash>&name=<deviceName>`
- Client scans QR, connects directly.
- Store the IP:port for reconnection without re-scanning.

### Discovery Strategy Decision Tree

```
Client needs to connect
        │
        ├─ mDNS (multicast query) ──── success? → Connected
        │       │
        │       └─ timeout (10s) ──┐
        │                          │
        ├─ UDP broadcast ──────────┼─ success? → Connected
        │       │                  │
        │       └─ timeout (5s) ──┤
        │                          │
        ├─ Try saved IPs ──────────┼─ success? → Connected
        │       │                  │
        │       └─ all fail ─────┐ │
        │                         ││
        └─ Show QR / manual IP ──┘│─→ Connected (manual)
```

---

## 3. Authentication

**Three-tier authentication** — clients authenticate once via a strong handshake, then maintain session tokens.

### 3.1 TLS Client Certificate (Best for Security)

| Aspect | Detail |
|---|---|
| **Trust model** | Server has a self-signed CA. Each client is issued a unique client cert signed by this CA. |
| **TLS handshake** | Server requests client certificate via `CertificateRequest` in TLS 1.3 handshake. |
| **Revocation** | Server maintains a CRL. Expired/revoked certs rejected at TLS layer. |
| **Android impl** | `KeyStore` with `KeyChain` or Bouncy Castle for cert generation. `SSLContext` with `TrustManager` and `KeyManager`. |

**Flow:**
1. Device generates a self-signed CA on first boot.
2. Out-of-band (QR scan, NFC tap, or manual copy) — client gets the CA cert and a client cert+key.
3. Client connects over TLS 1.3, presents client cert.
4. Server validates client cert against CA, extracts device identity from CN/SAN.
5. Connection is authenticated without additional round-trips.

**Pros:** No passwords or tokens to leak. Certificate is bound to the hardware/client. Revocation is possible. Mutual authentication is automatic.
**Cons:** Certificate management complexity. Hard to invalidate a compromised client without regenerating the CA. QR distribution of certs is UX friction.

### 3.2 Challenge-Response (Best for UX / Lightweight)

No passwords stored on device. Uses asymmetric crypto.

```
CLIENT                           SERVER
  │                                │
  │  POST /api/v1/auth/request     │
  │  {"clientId": "abc-123"}      │
  │ ──────────────────────────>   │
  │                                │
  │  Response                      │
  │  {"challenge": "7d8f...",     │
  │   "serverPubKey": "04ab..."}  │
  │ <──────────────────────────   │
  │                                │
  │  Client signs challenge       │
  │  with its private key         │
  │                                │
  │  POST /api/v1/auth/verify     │
  │  {"clientId": "abc-123",      │
  │   "signature": "3e9c..."}     │
  │ ──────────────────────────>   │
  │                                │
  │  Server verifies signature    │
  │  using client's public key    │
  │  (from pairing DB)            │
  │                                │
  │  Response                      │
  │  {"token": "eyJhbGci...",     │
  │   "expiresAt": 1711814400}    │
  │ <──────────────────────────   │
```

**Why challenge-response over simple token:**
- No shared secret stored on device. Device stores clients' public keys.
- Client proves identity without revealing a secret.
- Session token is issued after authentication for subsequent requests.

### 3.3 Token-Based (Simplest, for Lightweight Clients)

After initial pairing (challenge-response or QR exchange), the server issues a short-lived JWT:

```json
{
  "sub": "client-abc-123",
  "iat": 1711810800,
  "exp": 1711814400,
  "scope": "read:camera write:location"
}
```

**Token handling:**
- WebSocket: sent as first message after connection (`{"type":"auth","token":"..."}`), or as a query parameter in the WebSocket URL: `ws://192.168.1.42:8080/ws?token=...`
- HTTP REST: standard `Authorization: Bearer <token>` header.
- Tokens expire every 1 hour. Refresh via `POST /api/v1/auth/refresh` with the challenge-response mechanism.
- Server maintains a blacklist of revoked tokens (LRU cache, expiry-based cleanup).

### 3.4 Recommended Authentication Stack

```
Layer 1: TLS 1.3 with optional client certificates (encryption + identity)
Layer 2: Challenge-response handshake (identity proof)
Layer 3: JWT bearer tokens (session management)
```

**Default recommendation:** Challenge-response → JWT (no client certs, simpler UX).  
**Enterprise/high-security option:** Add client TLS certificates.

---

## 4. Encryption

### 4.1 TLS 1.3 — Mandatory, Even on LAN

**Never transmit plaintext even on a local network.** Consider:
- Rogue devices on the same WiFi (coffee shop, office, university network).
- ARP spoofing / MITM on switched Ethernet.
- WiFi packet capture (Wireshark on the same SSID captures all broadcast traffic).
- Compromised router forwarding traffic to external adversary.

**Configuration:**

| Parameter | Setting |
|---|---|
| Protocol | TLS 1.3 only (no fallback to 1.2) |
| Cipher suites | `TLS_AES_128_GCM_SHA256` (performance) or `TLS_CHACHA20_POLY1305_SHA256` (mobile, no AES hardware) |
| Server cert | Self-signed, generated on first boot. SHA-256 fingerprint used as device identity. |
| Key size | Ed25519 (fast, small signatures) or ECDSA P-256 |
| Cert renewal | Regenerate on factory reset. No CA hierarchy needed unless using client certs. |

**Self-signed cert generation on Android:**

```kotlin
val keyPairGenerator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
keyPairGenerator.initialize(
    KeyGenParameterSpec.Builder("server-auth", KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .build()
)
val keyPair = keyPairGenerator.generateKeyPair()
// Use keyPair to generate self-signed X.509 cert
```

### 4.2 Post-Quantum Consideration (Future)

For a system intended to last 5+ years, plan for:
- Hybrid key exchange (X25519 + ML-KEM) once Android supports it.
- TLS 1.3 allows post-quantal key exchange via `supported_groups` extension.

### 4.3 Certificate Pinning for Clients

Clients should pin the server's certificate hash to prevent MITM via forged CA:

```kotlin
// Android Client
val pin = CertificatePinner.Builder()
    .add("192.168.1.42", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    .build()
// In practice, pin on first connection (TOFU) or pre-configure during pairing
```

**Trust on First Use (TOFU):** On first connection, client saves the server's pubkey fingerprint. On subsequent connections, verifies fingerprint matches. If mismatch → reject and alert user.

### 4.4 Cipher Performance on Mobile

| Cipher | Android HW Accel | Speed (relative) | Battery Impact |
|---|---|---|---|
| AES-128-GCM | Yes (ARMv8 Crypto Extensions) | Fastest | Lowest |
| ChaCha20-Poly1305 | Yes (ARMv8) | Very fast | Very low |
| AES-256-GCM | Yes | 20% slower than AES-128 | Slightly higher |
| XChaCha20-Poly1305 | No (software) | Fast | Moderate |

**Recommendation:** `TLS_AES_128_GCM_SHA256` for devices with AES hardware (nearly all modern Android devices: Pixel, Samsung, OnePlus, etc.). `TLS_CHACHA20_POLY1305_SHA256` as secondary for older devices or CPU-bound scenarios.

---

## 5. Data Serialization

### 5.1 Evaluation

| Format | Binary? | Schema? | Overhead (100 B payload) | CPU Cost on Android | Frame Decode Speed |
|---|---|---|---|---|---|
| **JSON** | No | No | ~150% (250 B total) | Low (fast on modern JIT) | Instant (text) |
| **MessagePack** | Yes | No | ~10% (110 B total) | Very low | Very fast |
| **Protocol Buffers** | Yes | Yes (.proto) | ~5% (105 B total) | Low | Fast |
| **FlatBuffers** | Yes | Yes (.fbs) | 0% (zero-copy) | Lowest (no decode step) | Fastest |
| **CBOR** | Yes | Optional | ~15% (115 B total) | Low | Fast |

### 5.2 Recommendation: **Protocol Buffers (primary) + JSON (fallback)**

**Primary serializer: Protocol Buffers (protobuf)**

| Payload Type | Why Protobuf Wins |
|---|---|
| **Control messages** (JSON: location, contacts, device info) | Smaller wire size reduces WiFi packet count, saving battery |
| **Binary payloads** (camera frames, audio, screenshots) | Protobuf's `bytes` field carries raw data with minimal overhead |
| **Streaming chunks** | Fixed schema means no per-chunk field names sent over the wire |

**Schema design approach:**

```protobuf
// artemis.proto
syntax = "proto3";

message Frame {
  uint64 timestamp = 1;        // Unix millis
  uint32 sequence = 2;          // Monotonic sequence for ordering
  uint32 width = 3;
  uint32 height = 4;
  ImageFormat format = 5;       // JPEG, WEBP, RAW
  bytes data = 6;               // Compressed image frame
  optional int32 quality = 7;   // JPEG quality 1-100 (omitted = original)
}

message AudioChunk {
  uint64 timestamp = 1;
  uint32 sample_rate = 2;       // e.g., 44100
  uint32 channels = 3;
  AudioCodec codec = 4;         // PCM, OPUS, AAC
  bytes data = 5;
}

message Screenshot {
  uint64 timestamp = 1;
  uint32 display_id = 2;
  uint32 width = 3;
  uint32 height = 4;
  ImageFormat format = 5;
  bytes data = 6;
}

message DeviceStatus {
  double latitude = 1;
  double longitude = 2;
  float accuracy = 3;           // meters
  float battery_level = 4;      // 0.0 - 1.0
  int32 signal_strength = 5;    // dBm
  string wifi_ssid = 6;
  uint64 timestamp = 7;
}

// WebSocket message wrapper — single envelope for all message types
message WsMessage {
  oneof payload {
    Frame frame = 1;
    AudioChunk audio = 2;
    Screenshot screenshot = 3;
    DeviceStatus status = 4;
    CommandAck ack = 5;
    ErrorResponse error = 6;
    AuthRequest auth_req = 7;
    AuthResponse auth_resp = 8;
    ConfigUpdate config = 9;
  }
}
```

**Why not FlatBuffers:**
FlatBuffers are extremely fast (zero-copy decode), but:
- No streaming/delimited format (you must frame messages yourself).
- Schema evolution is harder (fields must be pre-assigned).
- The `.fbs` toolchain is less mature than `.proto`.
- For camera frames, the decode bottleneck is JPEG decompression, not wire format — FlatBuffers' advantage is marginal for this workload.

**Where JSON stays:**
- HTTP REST endpoints (`GET /api/v1/device/info`, `POST /api/v1/settings`).
- WebSocket initial handshake (auth token exchange).
- Health check responses.
- Debug logging and development (human-readable).

### 5.3 Binary Frame Compression

For camera frames and screenshots, don't compress in the serialization layer — compress at the codec level:

| Media | Recommended Codec | Typical Size (1080p) | Notes |
|---|---|---|---|
| Camera preview | JPEG (quality 60-80) | 50–200 KB | Hardware-accelerated on Android via `ImageReader` |
| Screenshot | WebP (lossy, quality 70) | 30–150 KB | Smaller than JPEG for screen content |
| Audio (voice) | Opus (16-bit, 16 kHz) | 16–32 KB/s | Excellent quality at low bitrates |
| Audio (music) | Opus (16-bit, 48 kHz) | 48–96 KB/s | Near-transparent at 96 kbps |

**Dynamic quality adaptation:**
- Monitor available bandwidth (track frame send time / receive ACK ratio).
- If frame send time > 100ms, reduce JPEG quality by 5.
- If frame send time < 20ms, increase quality by 5.
- Clamp quality to [30, 95].

---

## 6. Keep-Alive & Reconnection

### 6.1 Keep-Alive Strategy

| Layer | Mechanism | Interval | Purpose |
|---|---|---|---|
| **TCP** | `SO_KEEPALIVE` | System default (2 hours) — not useful | Kernel-level dead peer detection (too slow to rely on) |
| **TLS** | TLS heartbeat (optional) | — | Not commonly used in Android TLS stacks |
| **Application** | Ping/Pong (WebSocket native) | 15 seconds | Detect dead connections promptly |
| **Application** | Heartbeat message | 30 seconds | Additional health check, carries sequence number |

**WebSocket Ping/Pong:**

```
SERVER                    CLIENT
  │                         │
  │  Ping (opcode 0x9)     │
  │ ────────────────────>  │
  │                         │
  │  Pong (opcode 0xA)     │
  │ <────────────────────  │
  │                         │
  │  (if no Pong in 10s)   │
  │  → Close connection    │
  │  → Client reconnect    │
```

- **Server side:** Send WebSocket ping every 15 seconds. If no pong in 10 seconds, close connection.
- **Client side:** Respond to pings automatically (WebSocket libraries handle this). If no ping received in 30 seconds, treat as dead and reconnect.
- **Battery optimization:** Use Android's `AlarmManager` with `setExactAndAllowWhileIdle()` for the ping interval (coalesce with other work when possible).

### 6.2 Reconnection Strategy

**Exponential backoff with jitter:**

```kotlin
object ReconnectionStrategy {
    // Base delay: 1 second
    // Max delay: 60 seconds
    // Jitter: ±25%
    
    fun nextDelay(attempt: Int): Long {
        val base = (1 shl minOf(attempt, 6)) * 1000L  // 1, 2, 4, 8, 16, 32, 60s
        val jitter = (base * 0.25).toLong()
        return base + Random.nextLong(-jitter, jitter)
    }
}
```

| Attempt | Base Delay | With Jitter |
|---|---|---|
| 1 | 1s | 0.75–1.25s |
| 2 | 2s | 1.5–2.5s |
| 3 | 4s | 3–5s |
| 4 | 8s | 6–10s |
| 5 | 16s | 12–20s |
| 6 | 32s | 24–40s |
| 7+ | 60s | 45–75s |
| Max (30) | 60s | capped |

**Reconnection state machine:**

```
                    ┌──────────────┐
         Connect    │              │   Disconnect
     ┌────────────> │  CONNECTED   │ ──────────┐
     │              │              │           │
     │              └──────────────┘           │
     │                                         ▼
     │                              ┌──────────────────┐
     │                              │  DISCONNECTED    │
     │                              │  (start backoff) │
     │                              └──────────────────┘
     │                                         │
     │                                   backoff timer
     │                                         │
     │                                         ▼
     │                              ┌──────────────────┐
     │                              │  RECONNECTING    │
     │                              │  (connect attempt)│
     │                              └──────────────────┘
     │                                         │
     └─────────────────────────────────────────┘
                            success
```

**Session recovery on reconnect:**
1. Client reconnects to WebSocket.
2. Client sends stored session token (or re-authenticates via challenge-response).
3. Server responds with last sequence number per stream: `{"lastFrameSeq": 142, "lastAudioSeq": 89}`
4. Client resumes from that point (or starts fresh if data is time-sensitive like camera frames).
5. Server sends missed frames from its ring buffer (configurable: last 5 seconds or last 50 frames, whichever is smaller).

### 6.3 Network Change Handling

**Android network state changes (WiFi → mobile, IP address change):**

```kotlin
// Register ConnectivityManager callback
val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // New network — start discovery and reconnect
        triggerReconnect()
    }
    override fun onLost(network: Network) {
        // Current network lost — close all connections, start backoff
        closeAllConnections()
        startBackoff()
    }
    override fun onCapabilitiesChanged(
        network: Network,
        capabilities: NetworkCapabilities
    ) {
        // Network type changed (e.g., WiFi→mobile)
        // May need to re-discover on new subnet
        triggerReconnect()
    }
}
```

**Key behaviors:**
- On `onLost`: Immediately close all sockets (don't wait for TCP timeout). Start backoff.
- On `onAvailable`: Re-discover server via mDNS (new subnet might have different IPs). Reconnect.
- During transition: Clients buffered on-device frames (up to 5s ring buffer). On reconnect, flush buffered frames.
- IP address change: The server's IP changes (new DHCP lease). Clients must re-discover or use saved hostname.

### 6.4 Graceful Disconnection

- Server sends `{"type":"shutdown"}` to all clients 2 seconds before going down (app backgrounded, battery saver, update).
- Clients enter `DISCONNECTED` state immediately (no backoff), wait for push notification or periodic re-discovery.
- On app resume, server restarts listener and announces via mDNS again.

---

## 7. Concurrent Clients

### 7.1 Architecture for Multiple Clients

```
┌───────────────────────────────────────────┐
│            Android Server                 │
│                                           │
│  ┌─────────────────────────────────────┐  │
│  │   CoroutineDispatcher (Dispatchers.IO) │
│  │                                      │  │
│  │  ┌──────┐  ┌──────┐  ┌──────┐      │  │
│  │  │Client │  │Client │  │Client │ ... │  │
│  │  │  A   │  │  B   │  │  C    │      │  │
│  │  └──┬───┘  └──┬───┘  └──┬───┘      │  │
│  │     │          │         │          │  │
│  │  ┌──▼──────────▼─────────▼──┐       │  │
│  │  │    WebSocket Sessions    │       │  │
│  │  └──────────────────────────┘       │  │
│  │                                      │  │
│  │  ┌──────────────────────────┐       │  │
│  │  │   Frame Distribution    │       │  │
│  │  │   (Broadcast or Unicast)│       │  │
│  │  └──────────────────────────┘       │  │
│  │                                      │  │
│  │  ┌──────────────────────────┐       │  │
│  │  │   Camera / Audio Source   │       │  │
│  │  └──────────────────────────┘       │  │
│  └─────────────────────────────────────┘  │
└───────────────────────────────────────────┘
```

### 7.2 Per-Client Streams

Each client subscribes to a subset of streams:

```json
// Client sends subscription message after auth
{
  "type": "subscribe",
  "streams": {
    "camera": {"quality": 70, "max_fps": 15, "resolution": "480p"},
    "audio": {"enabled": false},
    "location": {"interval_ms": 1000},
    "screenshot": {"enabled": false}
  }
}
```

**Server maintains per-client state:**

```kotlin
class ClientSession(
    val clientId: String,
    val socket: WebSocketSession,
    val subscriptions: SubscriptionConfig,
    val authenticatedAt: Long,
    val lastActivity: AtomicLong,
    val sendChannel: Channel<WsMessage> // Backpressure buffer
)
```

### 7.3 Frame Distribution Strategy

| Strategy | When to Use | Pros | Cons |
|---|---|---|---|
| **Broadcast** | Multiple clients want same stream (e.g., camera) | Encode once, send to all | All clients get same quality/FPS |
| **Unicast per client** | Clients have different bandwidth/QoS needs | Tailored per client | Encode N times (CPU heavy) |
| **Hybrid** | Mix of both | Flexible | Complexity |

**Recommended: Hybrid approach**
- Encode camera at max quality. Cache the encoded frame.
- For each client, optionally re-encode at lower quality (use WebP for screen content, JPEG for camera).
- If all clients request same quality, send the cached frame directly (zero re-encode).

```kotlin
suspend fun distributeFrame(frame: EncodedFrame) {
    clients.withLock {
        for (session in activeClients) {
            if (!session.subscriptions.camera) continue
            
            val adaptedFrame = if (session.subscriptions.quality >= frame.quality) {
                frame // Send as-is
            } else {
                reencodeFrame(frame, session.subscriptions.quality)
            }
            
            session.sendChannel.send(adaptedFrame.toWsMessage())
        }
    }
}
```

### 7.4 Backpressure

**Critical for Android — don't let a slow client consume all memory.**

```kotlin
// Per-client send channel with bounded buffer
val sendChannel = Channel<WsMessage>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

// Writer side — always non-blocking
sendChannel.trySend(frameMessage)

// Reader side — in a coroutine
for (message in sendChannel) {
    try {
        socket.send(message) // with timeout
    } catch (e: IOException) {
        // client disconnected or too slow
        sendChannel.close()
        break
    }
}
```

**Backpressure mechanisms:**
- **Buffer:** 32-frame ring buffer per client (at 15 fps ≈ 2 seconds).
- **Drop:** `DROP_OLDEST` — drop the oldest unread frame, keep latest.
- **Feedback:** Server monitors send queue depth. If consistently > 20, signal client to reduce quality/FPS via a `backpressure` message.
- **Kick:** If client doesn't drain for 10 seconds, disconnect.

### 7.5 Connection Limits

| Resource | Limit | Rationale |
|---|---|---|
| Max concurrent WebSocket | 10 | Android battery/CPU — each connection consumes memory and CPU for TLS |
| Max HTTP connections | 20 | Short-lived, lower cost |
| Camera encode streams | 3 | Hardware encoder can handle 2–3 simultaneous streams |
| Audio encode streams | 5 | Opus encode is light (~5% CPU per stream) |

**When limit is reached:**
- New WebSocket connection receives HTTP 503 with `Retry-After: 30`.
- Existing clients get priority by `lastActivity` — oldest idle client gets disconnected first if limits hit.

### 7.6 Concurrency Model — Kotlin Coroutines

```kotlin
// Server — Ktor with Netty engine
fun Application.module() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(10)
        maxFrameSize = Long.MAX_VALUE // Handle large camera frames
    }
    
    install(CORS) { /* ... */ }
    install(ContentNegotiation) { /* protobuf + json */ }
    
    val clientManager = ClientManager()
    
    webSocket("/ws") { call ->
        // Each client gets its own coroutine
        val session = clientManager.register(this)
        try {
            handleClientSession(session, clientManager)
        } finally {
            clientManager.unregister(session.clientId)
        }
    }
}
```

**Threading model:**
- Ktor Netty engine uses event-loop threads (default: 2× CPU cores).
- Application code runs on `Dispatchers.IO` coroutine context (off the event loop).
- Heavy work (image encoding) dispatched to a dedicated `Dispatchers.Default` or custom thread pool.

---

## 8. Android Library Comparison

### 8.1 WebSocket Server Libraries

| Library | Stars | Size | Server Support | Performance | Battery | Ease of Use | Ktor/Netty? |
|---|---|---|---|---|---|---|---|
| **Ktor Server (Netty)** | ~13k | ~2.5 MB (netty) | Full HTTP+WS | ★★★★☆ | ★★★★☆ | ★★★★★ | Native |
| **NanoHTTPD** | ~6.8k | ~100 KB | HTTP only (WS addon) | ★★★☆☆ | ★★★★★ | ★★★★★ | None |
| **OkHttp WebSocket** | ~46k | ~500 KB (client only) | Client only | N/A | N/A | ★★★★★ | None |
| **Netty (direct)** | ~33k | ~5-15 MB | Full TCP/HTTP/WS | ★★★★★ | ★★★☆☆ | ★★☆☆☆ | Native |
| **Java-WebSocket** | ~10k | ~100 KB | WebSocket only | ★★★☆☆ | ★★★★☆ | ★★★★☆ | None |
| **Vert.x** | ~14k | ~8 MB | Full event loop | ★★★★★ | ★★★☆☆ | ★★★☆☆ | Netty |
| **Ratpack** | ~2k | ~3 MB | HTTP+WS | ★★★★☆ | ★★★☆☆ | ★★★★☆ | Netty |

### 8.2 Detailed Comparison

#### ✅ **Ktor Server (with Netty engine) — RECOMMENDED**

| Aspect | Rating | Notes |
|---|---|---|
| **Performance** | ★★★★☆ | Netty-based event loop. Handles ~5000 concurrent connections on mid-range phone. |
| **Battery** | ★★★★☆ | Event-loop architecture means no per-connection threads. CPU usage scales with active connections only. |
| **Ease of Use** | ★★★★★ | Kotlin DSL is intuitive. Coroutines make async code feel synchronous. |
| **WebSocket** | ★★★★★ | Built-in `webSocket {}` routing, ping/pong, frame size limits. |
| **HTTP REST** | ★★★★★ | Same server handles both WS and REST. |
| **Protobuf** | ★★★★☆ | Via Ktor ContentNegotiation plugin + `kotlinx-serialization`. |
| **TLS** | ★★★★★ | Built-in `install(SSLSupport)`. Auto-reload certs. |
| **Memory** | ~30–50 MB baseline | Some overhead from Netty's direct buffer pools. Tune with `-Dio.netty.allocator.maxOrder=3`. |
| **APK impact** | +2.5 MB | Netty engine JAR. OkHttp engine is smaller (+1 MB) but less performant. |
| **Min SDK** | API 21+ | Kotlin/Coroutines min API 21. |

**Verdict:** Best overall. Full-featured, well-tested, active development. The Ktor + Kotlin Coroutines + kotlinx.serialization stack is the modern Android server stack.

#### ✅ **NanoHTTPD**

| Aspect | Rating | Notes |
|---|---|---|
| **Performance** | ★★★☆☆ | One thread per connection model. OK for 1–5 concurrent clients. |
| **Battery** | ★★★★★ | Minimal overhead. No event loop, no thread pool overhead at low load. |
| **Ease of Use** | ★★★★★ | Single file, no dependencies. Extend `NanoHTTPD` and override `serve()`. |
| **WebSocket** | ★★☆☆☆ | No built-in WS. Need `NanoHTTPD-WebSocket` addon (3rd party, less maintained). |
| **HTTP REST** | ★★★★★ | Trivial to set up REST endpoints. |
| **Protobuf** | ★★★☆☆ | Manual. No content negotiation. |
| **TLS** | ★★★★☆ | Supports `SSLServerSocket` configuration. |
| **Memory** | ~5–10 MB | Extremely lightweight. |
| **APK impact** | +100 KB | Tiny. |

**Verdict:** Excellent for simple REST-only servers, or when minimizing APK size is critical. WebSocket support is second-class — don't use as primary if WS is required.

#### ⚠️ **Netty (direct)**

| Aspect | Rating | Notes |
|---|---|---|
| **Performance** | ★★★★★ | The gold standard. Zero-copy, epoll/kqueue native transport. |
| **Battery** | ★★★☆☆ | Powerful but power-hungry if not carefully tuned. Direct memory handling. |
| **Ease of Use** | ★★☆☆☆ | Steep learning curve. Netty's pipeline architecture is powerful but verbose. |
| **WebSocket** | ★★★★★ | Full control over WS handshake and frames. |
| **HTTP REST** | ★★★★★ | Full HTTP server (via HttpObjectAggregator). |
| **Protobuf** | ★★★★★ | Netty has Protobuf codec (`ProtobufDecoder` / `ProtobufEncoder`). |
| **TLS** | ★★★★★ | Netty's OpenSSL engine (boringssl) is fastest on Android. |
| **Memory** | ~50–100 MB | Direct buffer pools, native epoll. Can be heavy. |
| **APK impact** | +5–15 MB | Large. Netty + native transports. |

**Verdict:** Only if you need extreme throughput (1000s of frames/second) or custom protocol handling. Overkill and battery-heavy for typical use.

#### ❌ **OkHttp WebSocket (as server)**

OkHttp does not support running as a WebSocket server — it's a client library only. Many developers mistakenly try this. Don't.

#### ❌ **Java-WebSocket (NanoWebSocketServer)**

| Aspect | Rating | Notes |
|---|---|---|
| **Performance** | ★★★☆☆ | Selector-based, but less optimized than Netty. |
| **Battery** | ★★★★☆ | Reasonable for small numbers of clients. |
| **Ease of Use** | ★★★★☆ | Simple API for WS-only server. |
| **HTTP REST** | ★☆☆☆☆ | No built-in HTTP. Would need to hack it. |
| **TLS** | ★★★☆☆ | Via `SSLContext`. Manual configuration. |
| **Memory** | ~15–25 MB | Decent. |
| **APK impact** | +100 KB | Lightweight. |

**Verdict:** Decent if you only need WebSocket and don't want Ktor's dependency footprint. But lacks HTTP REST, so you'd need a second server.

### 8.3 Library Decision Matrix

| Requirement | Ktor | NanoHTTPD | Netty | Java-WS |
|---|---|---|---|---|
| WebSocket server | ✅ Built-in | ⚠️ Addon | ✅ Built-in | ✅ Native |
| HTTP REST | ✅ Built-in | ✅ Native | ✅ Built-in | ❌ |
| Protobuf | ✅ Plugin | ⚠️ Manual | ✅ Codec | ❌ |
| TLS 1.3 | ✅ Auto | ✅ Manual | ✅ Native | ⚠️ Manual |
| Keep-alive | ✅ Ping/Pong | ⚠️ Manual | ✅ Manual | ✅ Ping/Pong |
| Battery efficiency | ★★★★☆ | ★★★★★ | ★★★☆☆ | ★★★★☆ |
| Learning curve | Low | Lowest | High | Medium |
| APK size | +2.5 MB | +100 KB | +5–15 MB | +100 KB |
| Community | Large | Large | Very large | Medium |

### 8.4 Supporting Libraries

| Function | Library | Size | Role |
|---|---|---|---|
| **Protobuf** | `protobuf-javalite` | ~500 KB | Lightweight protobuf runtime for Android |
| **mDNS** | `android.nsd.NsdManager` (built-in) | 0 | No additional dependency |
| **Coroutines** | `kotlinx-coroutines-android` | ~400 KB | Async concurrency |
| **DataStore** | `androidx.datastore` | ~50 KB | Persist paired client keys |
| **Security** | `androidx.security:security-crypto` | ~200 KB | Encrypted shared preferences |
| **Camera** | `androidx.camera:camera-core` | ~300 KB | CameraX for frame capture |
| **JSON** | `kotlinx-serialization-json` | ~300 KB | HTTP REST serialization |
| **Networking** | `ktor-server-netty` | ~2.5 MB | Web server engine |
| **Image encode** | BitmapFactory / `android.graphics` (built-in) | 0 | JPEG/WebP encoding |

---

## 9. Recommended Architecture

### 9.1 Technology Stack

```
┌──────────────────────────────────────────────────────┐
│                   APPLICATION LAYER                  │
├──────────────────────────────────────────────────────┤
│                    Artemia App                        │
├──────────────────────────────────────────────────────┤
│  Discovery    Server       Stream     Client Manager │
│  (NsdMgr)    (Ktor+Netty) (Camera)   (Auth/Sub/BP)  │
├──────────────────────────────────────────────────────┤
│  kotlinx.serialization  │  protobuf-javalite         │
│  (JSON for REST)        │  (binary WS messages)      │
├──────────────────────────────────────────────────────┤
│          kotlinx.coroutines (Dispatchers)            │
├──────────────────────────────────────────────────────┤
│  Ktor Server (Netty engine)  │  OkHttp (client)      │
├──────────────────────────────────────────────────────┤
│  TLS 1.3  │  Android KeyStore  │  AndroidX Security  │
├──────────────────────────────────────────────────────┤
│              Android Runtime (API 26+)               │
└──────────────────────────────────────────────────────┘
```

### 9.2 Port Allocation

| Port | Protocol | Purpose | Configurable? |
|---|---|---|---|
| **8080** | TCP (TLS) | Primary WebSocket + HTTP REST | Yes |
| **9090** | UDP | Discovery broadcast listener | No (hardcoded) |
| **5353** | UDP | mDNS (shared with system) | No (IANA assigned) |

### 9.3 Endpoint Summary

```
WebSocket (wss://device:8080/ws)
├── Auth: {type: "auth", token: "..."}
├── Subscribe: {type: "subscribe", streams: {...}}
├── Unsubscribe: {type: "unsubscribe", stream: "camera"}
├── Command: {type: "command", action: "snapshot", params: {}}
├── KeepAlive: Ping/Pong (automatic)

HTTP REST (https://device:8080/api/v1/)
├── GET  /health               → Status (no auth)
├── GET  /device/info          → Device model, version
├── POST /auth/request         → Get challenge (no auth)
├── POST /auth/verify          → Submit signed challenge → JWT
├── POST /auth/refresh         → Refresh JWT (auth required)
├── GET  /history/frames       → Recorded frames (auth required)
├── GET  /history/audio        → Recorded audio (auth required)
├── GET  /settings             → Current settings
├── PUT  /settings             → Update settings
├── POST /pair                 → Initiate client pairing
├── DELETE /pair/:clientId     → Remove paired client
└── GET  /clients              → List connected clients
```

### 9.4 Message Flow Diagrams

**Client connection lifecycle:**

```
┌─────────┐                    ┌──────────┐
│  Client │                    │  Server  │
└────┬────┘                    └────┬─────┘
     │                              │
     │  ─── Discovery Phase ───     │
     │  mDNS query "_artemis._tcp"  │
     │ <──────────────────────────> │  (UDP multicast)
     │                              │
     │  Got IP: 192.168.1.42:8080  │
     │                              │
     │  ─── Connection Phase ────   │
     │  TCP connect :8080           │
     │ ──────────────────────────>  │
     │  TLS 1.3 handshake          │
     │ <═══════════════════════>   │
     │  (Server presents cert,     │
     │   client verifies TOFU)     │
     │                              │
     │  ─── Auth Phase ─────────    │
     │  POST /auth/request          │
     │ ──────────────────────────>  │
     │  {challenge, serverPubKey}   │
     │ <────────────────────────── │
     │                              │
     │  POST /auth/verify           │
     │  {signature, clientId}       │
     │ ──────────────────────────>  │
     │  {token, expiresAt}          │
     │ <────────────────────────── │
     │                              │
     │  ─── WebSocket Phase ────    │
     │  WS upgrade /ws?token=...   │
     │ ──────────────────────────>  │
     │  WS accepted (101)          │
     │ <────────────────────────── │
     │                              │
     │  {type: "subscribe", ...}   │
     │ ──────────────────────────>  │
     │  {type: "subscribed", ...}  │
     │ <────────────────────────── │
     │                              │
     │  ─── Data Streaming ────    │
     │  <camera frames, audio,     │
     │   location updates flow>    │
     │ <═════════════════════════  │
     │                              │
     │  Ping/Pong every 15s       │
     │ <═════════════════════════> │
```

**Camera frame distribution:**

```
     ┌────────┐    ┌──────────┐    ┌────────┐    ┌────────┐
     │CameraX │    │  Server  │    │Client A│    │Client B│
     └───┬────┘    └────┬─────┘    └───┬────┘    └───┬────┘
         │              │              │              │
         │  ImageReader │              │              │
         │  onImageAvail               │              │
         │ ──────────>  │              │              │
         │              │  Encode JPEG │              │
         │              │  (quality 80)│              │
         │              │  Wrap proto  │              │
         │              │              │              │
         │              │  Client A subscribed?      │
         │              │  ── yes, quality 80        │
         │              │  Send proto-frame          │
         │              │ ──────────────────────────> │
         │              │              │              │
         │              │  Client B subscribed?      │
         │              │  ── yes, but quality 50    │
         │              │  Re-encode JPEG at q50     │
         │              │  Send proto-frame          │
         │              │ ──────────────────────────> │
         │              │              │              │
```

---

## 10. Implementation Roadmap

### Phase 1: Foundation (Week 1–2)
- Set up Ktor server project with Netty engine.
- TLS 1.3 with self-signed cert (generated via Android KeyStore).
- Basic WebSocket echo server.
- UDP broadcast discovery listener.
- NsdManager service registration.

### Phase 2: Authentication (Week 3)
- Challenge-response handshake API (REST).
- Key pair generation and storage (AndroidKeyStore).
- JWT token generation and validation.
- WebSocket auth via first-message token.

### Phase 3: Data Streaming (Week 4–5)
- Protobuf schema definition and `protobuf-javalite` integration.
- Camera frame capture and streaming via WebSocket.
- Audio capture and Opus encoding.
- Screenshot capture (MediaProjection API).
- Frame distribution to multiple clients.

### Phase 4: Resilience (Week 6)
- Backpressure per client (bounded channel, drop oldest).
- Exponential backoff reconnection.
- Network change handling (ConnectivityManager callback).
- Session recovery on reconnect.
- Heartbeat/ping monitoring.

### Phase 5: Client Libraries (Week 7–8)
- Reference client library (Kotlin/Android).
- Web dashboard client (TypeScript, browser WebSocket).
- CLI client for testing.
- Client certificate auth (optional).

### Phase 6: Polish (Week 9–10)
- Battery optimization profiling.
- Performance tuning (encode quality adaptation).
- Discovery reliability improvements.
- Graceful shutdown / background mode.
- Penetration testing (rogue devices on LAN).

---

## Appendix A: Protobuf Schema (Full)

```protobuf
syntax = "proto3";

package artemis.v1;

// ── Enums ──────────────────────────────────────────────

enum ImageFormat {
  IMAGE_FORMAT_UNSPECIFIED = 0;
  JPEG = 1;
  WEBP_LOSSY = 2;
  WEBP_LOSSLESS = 3;
  PNG = 4;
}

enum AudioCodec {
  AUDIO_CODEC_UNSPECIFIED = 0;
  PCM_16 = 1;      // Raw 16-bit PCM
  OPUS = 2;         // Opus encoded (recommended)
  AAC_LC = 3;       // AAC-LC
}

enum CommandAction {
  COMMAND_UNSPECIFIED = 0;
  SNAPSHOT = 1;             // Take single photo
  START_RECORDING = 2;      // Start video recording
  STOP_RECORDING = 3;       // Stop video recording
  START_AUDIO_REC = 4;      // Start audio recording
  STOP_AUDIO_REC = 5;       // Stop audio recording
  REBOOT_SERVICE = 6;       // Restart server
  SHUTDOWN = 7;             // Graceful shutdown
}

enum ErrorCode {
  ERROR_UNSPECIFIED = 0;
  AUTH_FAILED = 1;
  TOKEN_EXPIRED = 2;
  RATE_LIMITED = 3;
  STREAM_UNAVAILABLE = 4;
  PERMISSION_DENIED = 5;
  INVALID_MESSAGE = 6;
  INTERNAL_ERROR = 7;
}

// ── Top-level message (oneof wrapper) ─────────────────

message WsMessage {
  oneof payload {
    // Control
    AuthRequest auth_req = 10;
    AuthResponse auth_resp = 11;
    SubscribeRequest subscribe = 12;
    SubscribeResponse subscribed = 13;
    UnsubscribeRequest unsubscribe = 14;
    CommandRequest command = 15;
    CommandAck ack = 16;
    ErrorResponse error = 17;
    Backpressure backpressure = 18;
    
    // Data streams
    Frame frame = 20;
    AudioChunk audio = 21;
    Screenshot screenshot = 22;
    DeviceStatus status = 23;
  }
  
  // Sequence number for ordering/dedup
  uint32 seq = 1;
  // Server timestamp (Unix millis)
  uint64 ts = 2;
}

// ── Auth Messages ─────────────────────────────────────

message AuthRequest {
  string token = 1;            // JWT bearer token
}

message AuthResponse {
  bool success = 1;
  string session_id = 2;
  uint64 expires_at = 3;       // Unix millis
  string error_message = 4;
}

message ChallengeRequest {
  string client_id = 1;
  bytes client_pub_key = 2;
}

message ChallengeResponse {
  bytes challenge = 1;
  bytes server_pub_key = 2;
  repeated string supported_auth_methods = 3;
}

message VerifyRequest {
  string client_id = 1;
  bytes signature = 2;         // Signed challenge
}

message VerifyResponse {
  string token = 1;            // JWT
  uint64 expires_at = 2;
}

// ── Subscription ──────────────────────────────────────

message SubscribeRequest {
  bool camera = 1;
  bool audio = 2;
  bool screenshot = 3;
  bool location = 4;
  
  // Per-stream config
  CameraConfig camera_config = 10;
  AudioConfig audio_config = 11;
  LocationConfig location_config = 12;
}

message CameraConfig {
  int32 quality = 1;           // JPEG/WebP quality 1-100
  int32 max_fps = 2;           // Max frames per second
  string resolution = 3;       // "480p", "720p", "1080p"
  string camera_id = 4;        // "0" = back, "1" = front
}

message AudioConfig {
  int32 sample_rate = 1;       // e.g., 44100
  AudioCodec codec = 2;
  bool voice_activity = 3;     // Only send when voice detected
}

message LocationConfig {
  int32 interval_ms = 1;       // Update interval in milliseconds
  float min_distance_m = 2;    // Minimum distance change to report
}

message SubscribeResponse {
  bool success = 1;
  string session_id = 2;
  string error_message = 3;
}

message UnsubscribeRequest {
  bool camera = 1;
  bool audio = 2;
  bool screenshot = 3;
  bool location = 4;
}

// ── Data Messages ─────────────────────────────────────

message Frame {
  uint64 timestamp = 1;
  uint32 sequence = 2;
  uint32 width = 3;
  uint32 height = 4;
  ImageFormat format = 5;
  bytes data = 6;
  optional int32 quality = 7;
}

message AudioChunk {
  uint64 timestamp = 1;
  uint32 sequence = 2;
  uint32 sample_rate = 3;
  uint32 channels = 4;
  AudioCodec codec = 5;
  bytes data = 6;
  bool is_final = 7;          // True for last chunk of recording
}

message Screenshot {
  uint64 timestamp = 1;
  uint32 sequence = 2;
  uint32 display_id = 3;
  uint32 width = 4;
  uint32 height = 5;
  ImageFormat format = 6;
  bytes data = 7;
}

message DeviceStatus {
  double latitude = 1;
  double longitude = 2;
  float accuracy_m = 3;
  float battery_level = 4;     // 0.0 - 1.0
  float battery_temperature_c = 5;
  int32 signal_strength_dbm = 6;
  int32 wifi_rssi = 7;
  string wifi_ssid = 8;
  bool is_charging = 9;
  uint64 timestamp = 10;
}

// ── Control Messages ──────────────────────────────────

message CommandRequest {
  CommandAction action = 1;
  map<string, string> params = 2;
}

message CommandAck {
  CommandAction action = 1;
  bool success = 2;
  string message = 3;
  optional bytes result_data = 4;  // For SNAPSHOT, returns frame bytes
}

message ErrorResponse {
  ErrorCode code = 1;
  string message = 2;
  string details = 3;
}

message Backpressure {
  string stream = 1;           // "camera", "audio", etc.
  int32 queue_depth = 2;       // Current buffered frames
  bool drop_rate = 3;          // True → server is dropping frames for this client
  // Suggested client action
  oneof suggestion {
    int32 reduce_quality = 4;  // Suggested JPEG quality
    int32 reduce_fps = 5;     // Suggested max FPS
    int32 reduce_resolution = 6; // Suggested resolution index
  }
}

// ── Pairing / Config ──────────────────────────────────

message PairingInfo {
  string client_id = 1;
  string client_name = 2;
  bytes client_pub_key = 3;
  uint64 paired_at = 4;
  repeated string permissions = 5;  // "camera", "audio", "location"
}

message ConfigUpdate {
  int32 camera_quality = 1;
  int32 max_clients = 2;
  bool pairing_enabled = 3;
  string device_name = 4;
}
```

## Appendix B: Dependency Versions (Gradle)

```kotlin
// build.gradle.kts (app module)
dependencies {
    // Server
    implementation("io.ktor:ktor-server-netty:2.3.x")
    implementation("io.ktor:ktor-server-websockets:2.3.x")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.x")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf:2.3.x")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.x")
    implementation("io.ktor:ktor-server-auth:2.3.x")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.x")
    implementation("io.ktor:ktor-server-cors:2.3.x")
    
    // Protobuf
    implementation("com.google.protobuf:protobuf-javalite:3.25.x")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.x")
    
    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.x")
    
    // Discovery
    // (uses built-in android.net.nsd.NsdManager — no extra dependency)
}
```

---

## Appendix C: References

1. **Android NsdManager documentation** — https://developer.android.com/training/connect-devices-wirelessly/nsd
2. **Ktor Server documentation** — https://ktor.io/docs/server.html
3. **Protocol Buffers for Android** — https://developers.google.com/protocol-buffers/docs/javalite
4. **TLS 1.3 RFC 8446** — https://datatracker.ietf.org/doc/html/rfc8446
5. **WebSocket RFC 6455** — https://datatracker.ietf.org/doc/html/rfc6455
6. **Kotlin coroutines for Android** — https://kotlinlang.org/docs/android.html
7. **mDNS (RFC 6762)** — https://datatracker.ietf.org/doc/html/rfc6762
8. **Android battery optimization guide** — https://developer.android.com/topic/performance/power
9. **FlatBuffers vs Protobuf performance** — https://google.github.io/flatbuffers/flatbuffers_benchmarks.html
