# Android Local Network Server App — Complete Tech Stack Research

**Target:** Android 10+ (API 29+), minimum viable API 26 (Android 8.0)
**Architecture pattern:** Foreground Service + HTTP/WebSocket server + sensor/peripheral access layer
**Generated:** 2026-07-30

---

## Table of Contents

1. [HTTP/WebSocket Server in Foreground Service](#1-httpwebsocket-server-in-foreground-service)
2. [Camera Access (Camera2 / CameraX)](#2-camera-access-camera2--camerax)
3. [Microphone Recording](#3-microphone-recording)
4. [Location Tracking (Fused Location Provider)](#4-location-tracking-fused-location-provider)
5. [Call Recording via MediaRecorder](#5-call-recording-via-mediarecorder)
6. [Screen Capture via MediaProjection](#6-screen-capture-via-mediaprojection)
7. [Contacts / SMS Reading via ContentResolvers](#7-contacts--sms-reading-via-contentresolvers)
8. [File System Access](#8-file-system-access)
9. [Encrypted Local Storage](#9-encrypted-local-storage)
10. [Token-Based Authentication](#10-token-based-authentication)
11. [Complete Manifest Permissions Reference](#11-complete-manifest-permissions-reference)
12. [Key Pitfalls & Design Recommendations](#12-key-pitfalls--design-recommendations)

---

## 1. HTTP/WebSocket Server in Foreground Service

### Android APIs & Components

| Component | API / Class | Min API |
|-----------|-------------|---------|
| Foreground Service | `android.app.Service` + `startForeground()` | API 26 (required for all foreground services) |
| Service Type Declaration | `<service android:foregroundServiceType="...">` | API 29 (required), API 34+ restricts which types |
| Notification Channel | `NotificationChannel` + `Notification.Builder` | API 26 (mandatory) |
| HTTP Server | **NanoHTTPD** (3rd party) or raw `ServerSocket` | API 1 |
| WebSocket | **Java-WebSocket** (3rd party) or raw `Socket` | API 1 |

### Recommended Libraries

| Library | Artifact | Min API | Notes |
|---------|----------|---------|-------|
| **NanoHTTPD** | `org.nanohttpd:nanohttpd:2.3.1` | API 1 | Single-file HTTP server, ~30KB, full HTTP/1.1 + Websocket |
| **Java-WebSocket** | `org.java-websocket:Java-WebSocket:1.5.4` | API 7+ | RFC 6455 WebSocket client+server |
| **Ktor** (Kotlin) | `io.ktor:ktor-server-netty:2.3.x` | API 21+ | Coroutine-based, heavy (~2MB) |
| **OkHttp** (server side) | `com.squareup.okhttp3:okhttp:4.12.x` | API 21+ | MockWebServer for testing, WebSocket client |
| **ktor-server-cio** | `io.ktor:ktor-server-cio:2.3.x` | API 21+ | CIO engine, lighter than Netty |

### Required Manifest Declarations

```xml
<!-- Foreground service type: required from API 34 (Android 14) -->
<service
    android:name=".ServerForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />

<!-- Required permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- Notifications (required for foreground service) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Permission Requirements

| Permission | Since | Purpose |
|-----------|-------|---------|
| `FOREGROUND_SERVICE` | API 28 (P) | Required to start any foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | API 34 (14) | Required when `foregroundServiceType="dataSync"` |
| `POST_NOTIFICATIONS` | API 33 (13) | Required to show the persistent notification |
| `INTERNET` | API 1 | Required for server socket binding |
| `ACCESS_NETWORK_STATE` | API 1 | For network interface discovery |

### Known Limitations

- **Android 8+ (API 26):** Background service restrictions — apps cannot start background services. Foreground services must call `startForeground()` within ~5 seconds of `onStartCommand()` or the service is killed and marked as ANR.
- **Android 12+ (API 31):** Foreground service launch restrictions — background-started foreground services are blocked unless the app has a visible window or uses an allowed exemption (e.g., `dataSync` type, or scheduled with `WorkManager`).
- **Android 14+ (API 34):** Foreground service types are strictly enforced. `dataSync` must be declared. A service declared as `dataSync` can run indefinitely but must be justified at app review time (Google Play policy). Services declared as `shortService` are killed after ~3 minutes.
- **Android 15+ (API 35):** 6-hour foreground service timeout for `dataSync` / `camera` / `microphone` — service is stopped by the system after 6 hours of continuous foreground execution. Must use `WorkManager` or restart logic.
- **Network port conflicts:** Android does not allow binding to ports < 1024 without root. Use port 8080, 8443, or similar high ports.
- **Wi-Fi → Mobile switch:** The server socket IP changes when network interfaces switch. Must register `ConnectivityManager.NetworkCallback` to detect changes and rebind.
- **Doze mode:** In Doze, network access is deferred. Use `PowerManager.WakeLock` (partial wake lock on server thread) and keep the app's network access whitelisted via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

---

## 2. Camera Access (Camera2 / CameraX)

### Android APIs

| API | Library | Min API | Best For |
|-----|---------|---------|----------|
| **CameraX** (Jetpack) | `androidx.camera:camera-camera2` + `camera-lifecycle` + `camera-view` | API 21 | New apps, 98%+ device coverage |
| **Camera2** | `android.hardware.camera2.CameraManager` | API 21 | Fine-grained control, raw capture |
| Camera1 (deprecated) | `android.hardware.Camera` | API 1 (deprecated API 21) | Do not use |

### Gradle Dependencies (CameraX — recommended)

```kotlin
// Core CameraX
implementation("androidx.camera:camera-core:1.4.1")
implementation("androidx.camera:camera-camera2:1.4.1")
implementation("androidx.camera:camera-lifecycle:1.4.1")
implementation("androidx.camera:camera-view:1.4.1")

// Video capture
implementation("androidx.camera:camera-video:1.4.1")

// CameraX Extensions (bokeh, HDR, night mode)
implementation("androidx.camera:camera-extensions:1.4.1")
```

### Required Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />

<!-- Required for devices that may not have a camera -->
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

### Known Limitations

- **API 21 minimum:** Both CameraX and Camera2 require API 21.
- **CameraX lifecycle binding:** CameraX requires a `LifecycleOwner` (Activity or Fragment) — camera lifecycle management in a pure foreground service requires careful workaround. Use a `ProcessCameraProvider` and manual lifecycle control via `ListenableFuture` or use Camera2 directly from a Service context.
- **For Service use:** Camera2's `CameraManager.openCamera()` works in a foreground service. CameraX can work with `androidx.camera.core.CameraX` in manual mode but this is not officially documented.
- **Background camera access blocked:** Android 9+ (API 28) restricts camera access when app is in background. A foreground service with `foregroundServiceType="camera"` (API 34+) is allowed.
- **Multiple concurrent cameras:** Legacy limited support. CameraX handles this better.
- **Permissions runtime:** CAMERA is a "dangerous" permission — must be requested at runtime from API 23+.
- **Android 14+ (API 34):** If camera is accessed from a foreground service, the service must declare `android:foregroundServiceType="camera"` and request `FOREGROUND_SERVICE_CAMERA` permission.

---

## 3. Microphone Recording

### Android APIs

| API | Class | Min API | Purpose |
|-----|-------|---------|---------|
| MediaRecorder | `android.media.MediaRecorder` | API 1 | Simple audio capture to file |
| AudioRecord | `android.media.AudioRecord` | API 3 | Raw PCM audio streaming |
| AudioManager | `android.media.AudioManager` | API 1 | Audio routing, volume control |

### Required Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### Recommended Approach for Server

For streaming microphone audio to HTTP/WebSocket clients, use **AudioRecord** with a buffer callback pattern:

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    44100,                                  // Sample rate
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)
audioRecord.startRecording()
// Read PCM frames in a loop, stream to WebSocket clients
```

### Known Limitations

- **API 23+:** RECORD_AUDIO is a dangerous permission — requires runtime request.
- **AudioSource.MIC processing:** Most audio sources (including `MIC` and `DEFAULT`) apply AGC (automatic gain control), noise suppression, and other processing. Use `MediaRecorder.AudioSource.UNPROCESSED` (API 26+) for raw audio. Check `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` first.
- **Multiple apps recording:** Starting with Android 10 (API 29), the system can support multiple apps recording simultaneously, but this depends on device hardware.
- **Headphone/Bluetooth routing:** Audio input may switch between built-in mic, headset mic, and Bluetooth headsets. Register `AudioManager.registerAudioDeviceCallback()` to detect changes.
- **Background recording:** A foreground service with `foregroundServiceType="microphone"` (API 34+) is required. Without the `microphone` type declaration on API 34+, the system kills the service.
- **API 34+:** Requires `FOREGROUND_SERVICE_MICROPHONE` permission.
- **API 35+:** 6-hour timeout applies to `microphone`-type foreground services.
- **Buffer size:** Must calculate or query `AudioRecord.getMinBufferSize()`. Too small = glitches, too large = latency.

---

## 4. Location Tracking (Fused Location Provider)

### Android APIs

| API | Library | Min API | Purpose |
|-----|---------|---------|---------|
| **Fused Location Provider** | `com.google.android.gms:play-services-location:21.x` | API 14+ | Battery-efficient location |
| LocationManager (platform) | `android.location.LocationManager` | API 1 | Fallback, less accurate |
| **Google Play services** | Full suite required | Varies | FLP dependency |
| Activity Recognition | `com.google.android.gms:play-services-location` | API 16 | Movement detection |

### Required Permissions

```xml
<!-- Foreground (in-use) location — API 29+ -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Background location — API 29+ -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Foreground service type for location — API 34+ -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

### Implementation

```kotlin
// Gradle
implementation("com.google.android.gms:play-services-location:21.4.0")

// Get FusedLocationProviderClient
val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

// Request location updates
val locationRequest = LocationRequest.Builder(
    Priority.PRIORITY_HIGH_ACCURACY,
    5000  // interval in ms
).apply {
    setMinUpdateIntervalMillis(2000)
    setMaxUpdateDelayMillis(10000)
}.build()

fusedLocationClient.requestLocationUpdates(
    locationRequest,
    locationCallback,
    Looper.getMainLooper()
)
```

### Known Limitations

- **API 29+ (Android 10):** Background location (`ACCESS_BACKGROUND_LOCATION`) is a separate permission that must be requested independently. The system dialog does not prompt for background location if you request it at the same time as foreground location.
- **API 31+ (Android 12):** Location *while in use* (foreground) — you can request `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`. Background location requires a dedicated flow and app review. The system also adds a "granular" permission screen (Precise / Approximate).
- **API 34+ (Android 14):** Foreground service must declare `foregroundServiceType="location"` and `FOREGROUND_SERVICE_LOCATION` permission. Without this, the service is killed.
- **Google Play Services dependency:** Fused Location Provider requires Google Play Services on the device. For non-GMS devices (Huawei, some Chinese ROMs), use the platform `LocationManager` API as fallback.
- **Battery optimization:** Android 9+ applies location throttling for background apps. Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or use geofences for power-efficient background location.
- **Privacy indicator:** Android 12+ shows a green dot when the app accesses location (along with camera/mic).
- **API 35+:** 6-hour foreground service timeout for `location` type.

---

## 5. Call Recording via MediaRecorder

### Android APIs

| API | Class | Min API | Purpose |
|-----|-------|---------|---------|
| MediaRecorder | `android.media.MediaRecorder` | API 1 | Audio capture |
| TelephonyManager | `android.telephony.TelephonyManager` | API 1 | Call state detection |
| PhoneStateListener | `android.telephony.PhoneStateListener` | API 1 | Listen for call events |
| **VoicemailContract** | `android.provider.VoicemailContract` | API 14 | Voicemail content |

### Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### Audio Sources for Call Recording

| AudioSource | Min API | Description |
|-------------|---------|-------------|
| `MediaRecorder.AudioSource.VOICE_CALL` | API 1 | **Deprecated from API 29+** — may not work |
| `MediaRecorder.AudioSource.VOICE_DOWNLINK` | API 1 | Downlink (remote party) |
| `MediaRecorder.AudioSource.VOICE_UPLINK` | API 1 | Uplink (local mic) |
| `MediaRecorder.AudioSource.MIC` | API 1 | Microphone only (works, but captures local audio + ambient) |

### Critical Restrictions (Android 10+)

> **Call recording is significantly restricted starting with Android 10 (API 29):**

1. **`CAPTURE_AUDIO_OUTPUT` permission** — This signature-level permission (also known as `android.permission.CAPTURE_AUDIO_OUTPUT`) is **not available to third-party apps**. It is granted only to system apps / OEM-preinstalled apps. Normal apps *cannot* obtain it.

2. **`VOICE_CALL` / `VOICE_DOWNLINK` / `VOICE_UPLINK` sources —** These audio sources were deprecated in API 29. On Android 10+:
   - They may return a **"permission denied"** error.
   - On some OEMs they return silence (no remote-party audio).
   - Google Play prohibits apps that primarily record calls from publishing.

3. **Accessibility Service approach:** Some call recording apps bypass restrictions using `AccessibilityService` + audio capture. Google Play policy increasingly restricts this. Not viable for long-term production.

4. **What works on Android 10+:**
   - **MIC source only** — records the local microphone. Captures the user's voice and ambient sound. **Does not capture the remote caller's voice** (on most devices).
   - Speakerphone + MIC — if the user puts the call on speakerphone, the MIC can pick up the remote audio, but quality is poor.
   - **VOIP calls (WhatsApp, Signal, Telegram):** These are not "calls" in the telephony sense. MIC source works normally to record what goes through the device speaker.

5. **Region-specific legality:** Call recording is illegal in many jurisdictions without both parties' consent (two-party consent states in US, EU GDPR, etc.). Even where legal (e.g., one-party consent), app store policies may reject.

### Actual Working Approach (for what works)

```kotlin
// YouTube-style recording: MIC + speakerphone
// 1. Detect call state via TelephonyManager
// 2. Switch speakerphone on
// 3. Record using MediaRecorder with AudioSource.MIC
// Result: captures local voice + remote voice through speaker, poor quality

// VOIP call recording (WhatsApp, etc.):
val recorder = MediaRecorder()
recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
recorder.setOutputFile(filePath)
recorder.prepare()
recorder.start()
```

### Honest Summary

| Scenario | Works on A10+? | Capture remote side? | Notes |
|----------|---------------|---------------------|-------|
| MIC source | ✅ Yes | ❌ No | Local only |
| VOICE_CALL source | ❌ Blocked | ❌ No | Deprecated, returns silence |
| CAPTURE_AUDIO_OUTPUT | ❌ System-only | ✅ Yes | Signature permission |
| Accessibility Service | ⚠️ Limited | ⚠️ Partial | Google Play policy risk |
| Speakerphone + MIC | ⚠️ Works | ⚠️ Poor quality | User must enable speakerphone |
| **VOIP app recording** | ✅ Yes (MIC) | ✅ Captures output via speaker | If speakerphone on |

**Recommendation:** Do not rely on call recording for a production app. If required, limit to MIC-only recording and clearly document the limitation. For VOIP calls, MIC recording works normally as the app is the call endpoint.

---

## 6. Screen Capture via MediaProjection API

### Android APIs

| API | Class | Min API | Purpose |
|-----|-------|---------|---------|
| **MediaProjection** | `android.media.projection.MediaProjection` | API 21 | Screen capture |
| **MediaProjectionManager** | `android.media.projection.MediaProjectionManager` | API 21 | User consent prompt |
| ImageReader | `android.media.ImageReader` | API 19 | Read captured frames |
| VirtualDisplay | `android.hardware.display.VirtualDisplay` | API 21 | Virtual display for capture |
| MediaCodec | `android.media.MediaCodec` | API 16 | Encode frames to video |

### Required Permissions

```xml
<!-- No manifest permission — user consent is obtained at runtime via system dialog -->

<!-- Storage permission only if saving to shared storage -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"
    android:minSdkVersion="33" />
```

### Implementation Flow

```kotlin
// 1. Get MediaProjectionManager
val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

// 2. Start intent (opens system consent dialog — requires Activity)
val intent = projectionManager.createScreenCaptureIntent()
startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)

// 3. In onActivityResult, create MediaProjection
val mediaProjection = projectionManager.getMediaProjection(resultCode, data)

// 4. Create VirtualDisplay
val displayMetrics = resources.displayMetrics
val virtualDisplay = mediaProjection.createVirtualDisplay(
    "ScreenCapture",
    displayMetrics.widthPixels,
    displayMetrics.heightPixels,
    displayMetrics.densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
    imageReader.surface,  // or MediaCodec surface for recording
    null, null
)

// 5. Read frames from ImageReader
imageReader.setOnImageAvailableListener({ reader ->
    val image = reader.acquireLatestImage()
    // Process image.planes
    image?.close()
}, backgroundHandler)
```

### Known Limitations

- **Requires Activity for consent prompt:** The `MediaProjectionManager.createScreenCaptureIntent()` must be called from an Activity context (not a Service). After consent, the `resultCode` and `data` (Intent) can be passed to a foreground service via `startService()`.
- **API 21+:** MediaProjection is only available from Android 5.0.
- **User consent dialog:** Every time the app requests screen capture, the user sees a "start recording or casting" dialog. The user must explicitly approve.
- **No background capture:** Screen capture requires ongoing user consent. If the activity that requested the intent is destroyed, some implementations stop. Must pass the `resultCode`+`data` to a long-lived service.
- **Audio capture limitation:** MediaProjection can capture device audio output (since API 29 `AudioPlaybackCaptureConfiguration`) but it's complicated to set up and requires `CAPTURE_AUDIO_OUTPUT` policy or a usage filter.
- **Scoped storage:** Captured images/videos must go through MediaStore on API 29+ or app-specific directory.
- **Android 12+:** The system shows a persistent indicator (orange dot + screen recording chip) when screen capture is active. The chip allows the user to stop capture immediately.
- **Security:** The screen contents (passwords, notifications, banking apps) are exposed to your app. Google Play review checks for legitimate use cases.
- **Performance:** Reading screen frames at 30+ FPS is CPU/IO intensive. Use `MediaCodec` with a surface directly (encoder input) rather than `ImageReader` for video recording to avoid extra buffer copies.
- **Multiple displays:** API 27+ supports `createVirtualDisplay` on specific displays.

---

## 7. Contacts / SMS Reading via ContentResolvers

### Contacts

| Class | Min API | Description |
|-------|---------|-------------|
| `ContactsContract.Contacts` | API 5 | Contact records |
| `ContactsContract.CommonDataKinds.Phone` | API 5 | Phone numbers |
| `ContactsContract.CommonDataKinds.Email` | API 5 | Email addresses |
| `ContactsContract.CommonDataKinds.StructuredName` | API 5 | Contact names |

### SMS

| Class | Min API | Description |
|-------|---------|-------------|
| `Telephony.Sms.Inbox` | API 19 | SMS inbox |
| `Telephony.Sms.Sent` | API 19 | Sent SMS |
| `Telephony.Sms.Draft` | API 19 | Draft SMS |
| `Telephony.TextBasedSmsColumns` | API 19 | Column constants |

### Required Permissions

```xml
<!-- Contacts -->
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />

<!-- SMS (API 19+) -->
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.SEND_SMS" />

<!-- Required for reading phone number and device identifiers (API 26+) -->
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

### Implementation (Contacts)

```kotlin
val cursor = contentResolver.query(
    ContactsContract.Contacts.CONTENT_URI,
    null, null, null, null
)
cursor?.use {
    while (it.moveToNext()) {
        val id = it.getString(it.getColumnIndex(ContactsContract.Contacts._ID))
        val name = it.getString(it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))

        // Query phone numbers for this contact
        val phones = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null
        )
    }
}
```

### Implementation (SMS)

```kotlin
val cursor = contentResolver.query(
    Telephony.Sms.Inbox.CONTENT_URI,
    null, null, null,
    "${Telephony.Sms.Inbox.DEFAULT_SORT_ORDER}"
)
cursor?.use {
    while (it.moveToNext()) {
        val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
        val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))
        val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE))
    }
}
```

### Known Limitations

- **API 23+:** READ_CONTACTS and READ_SMS are dangerous permissions — require runtime request. Starting API 30 (Android 11), READ_SMS is further restricted; apps that do not have the default SMS role cannot read SMS unless the user grants the permission.
- **API 30+ (Android 11):** **SMS/Call Log restrictions** — Apps that are NOT the default SMS app cannot read SMS. They must request the `ROLE_SMS` role via `RoleManager.createRequestRoleIntent()`. This is a significant limitation. The same applies to call log (`READ_CALL_LOG`).
- **API 30+ permission auto-reset:** If the user does not interact with your app for a few months, permissions are auto-revoked.
- **Performance:** Querying the entire contacts DB can be slow (10k+ contacts). Use `ContentResolver` with proper projection, selection args, and pagination.
- **Privacy labels:** Google Play data safety section must disclose contacts and SMS access with justification.
- **One-time permission:** API 30+ introduces "only this time" permission option; apps should gracefully handle this.
- **SMS content for 2FA:** Many devices block SMS access for non-default SMS apps for security. Use `SmsRetriever` API (Google Play Services) for 2FA SMS instead.

---

## 8. File System Access

### Android APIs by Storage Type

| Storage Type | API / Path | Permissions Needed | Min API | Persists After Uninstall |
|-------------|-----------|-------------------|---------|--------------------------|
| **App-specific internal** | `context.filesDir` / `context.cacheDir` | None | API 1 | ❌ Deleted |
| **App-specific external** | `context.getExternalFilesDir(null)` | None (API 19+, read/write own) | API 8 | ❌ Deleted |
| **MediaStore (shared)** | `MediaStore.Images`, `MediaStore.Video`, `MediaStore.Audio` | `READ_MEDIA_IMAGES` etc. | API 29 (v2) | ✅ Persists |
| **Documents (SAF)** | `Intent(Intent.ACTION_OPEN_DOCUMENT)` | None (user picks) | API 19 | ✅ Persists |
| **MANAGE_EXTERNAL_STORAGE** | `/storage/emulated/0/...` | `MANAGE_EXTERNAL_STORAGE` (special access) | API 30 | ✅ Persists |

### Recommended: App-Specific Storage (No Permissions Needed)

```kotlin
// Internal — encrypted, isolated, no permissions
val file = File(context.filesDir, "server-data/config.json")
file.writeText(jsonData)

// External — larger capacity, still app-scoped
val logFile = File(context.getExternalFilesDir("logs"), "access.log")
logFile.appendText(logEntry)
```

### Required Permissions

```xml
<!-- Legacy full storage (API < 29) -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<!-- Granular media permissions (API 33+) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

<!-- Full file access (API 30+ — special app access, NOT normal) -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

### Scoped Storage Restrictions (API 29+)

| Aspect | What Changed |
|--------|-------------|
| **API 29 (Android 10)** | Scoped storage introduced. Apps cannot freely access `/sdcard/`. Use MediaStore or app-specific directories. |
| **API 30 (Android 11)** | Scoped storage enforced. `requestLegacyExternalStorage="true"` no longer works (was a temporary opt-out in API 29). |
| **API 33 (Android 13)** | Granular media permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` replace `READ_EXTERNAL_STORAGE`. |
| **MANAGE_EXTERNAL_STORAGE** | Required for "file manager" type apps. Google Play review requires a legitimate use case (antivirus, file manager, backup). A server app likely qualifies if it needs to serve arbitrary user files. |

### Known Limitations

- **App-specific storage is deleted on uninstall.** For data that should survive uninstall (user recordings, etc.), use MediaStore or SAF.
- **MediaStore bulk operations are slow.** For many small files, use app-specific storage.
- **MANAGE_EXTERNAL_STORAGE** triggers a **Special app access** screen in Settings. Many users deny it. Google Play requires justification.
- **directories:** `getExternalFilesDir()` returns `null` if external storage is not mounted.
- **FileProvider:** To serve files from app-specific storage to other apps (e.g., HTTP server sends files), use `FileProvider` with `FLAG_GRANT_READ_URI_PERMISSION`.

---

## 9. Encrypted Local Storage

### Options

| Solution | Type | Min API | Encryption | Performance | Notes |
|----------|------|---------|------------|-------------|-------|
| **EncryptedSharedPreferences** | Jetpack Security | API 23 | AES256-GCM-None via AndroidKeyStore | Fast | Key-value, small data only |
| **SQLCipher** | 3rd party | API 14 | AES256-CBC-HMAC-SHA1/SHA256 | Medium | Full SQLite encryption |
| **Room + SQLCipher** | Jetpack + 3rd party | API 14 | Same as SQLCipher | Medium | ORM layer over SQLCipher |
| **Tink** | Google 3rd party | API 19 | AEAD, streaming, KMS | Fast | Files, keysets |
| **AndroidKeyStore** | Platform | API 18 (improved API 23) | AES/GCM/EC | Fast | Key management only |
| **File-level encryption** (FBE) | Platform | API 24 (Direct Boot) | AES-XTS | OS-level | Transparent, built-in |

### Recommended Solution: Jetpack Security (EncryptedSharedPreferences + EncryptedFile)

**Gradle:**
```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

**EncryptedSharedPreferences:**
```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val sharedPreferences = EncryptedSharedPreferences.create(
    context,
    "server_secure_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// Token storage
sharedPreferences.edit().putString("auth_token", token).apply()
```

**EncryptedFile:**
```kotlin
val encryptedFile = EncryptedFile.Builder(
    context,
    File(context.filesDir, "db/encrypted_data.bin"),
    masterKey,
    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
).build()
```

### SQLCipher (for structured data requiring queries)

**Gradle:**
```kotlin
implementation("net.zetetic:android-database-sqlcipher:4.5.6")
implementation("androidx.sqlite:sqlite-ktx:2.4.0")
```

**Usage:**
```kotlin
SQLiteDatabase.openOrCreateDatabase(
    dbFile,          // java.io.File
    "passphrase",    // Key — derive from AndroidKeyStore, never hardcode
    null
)
```

**Room + SQLCipher (recommended for complex data):**
```kotlin
val passphrase = MasterKey.Builder(context).build().let { key ->
    // Derive as bytes from key; SQLCipher needs a char[] or String
    CryptographyHandler.derivePassphrase(key)
}

SupportFactory(supportFactory = net.zetetic.database.sqlcipher.SupportFactory(passphrase))
Room.databaseBuilder(context, AppDatabase::class.java, "encrypted.db")
    .openHelperFactory(supportFactory)
    .build()
```

### Known Limitations

- **EncryptedSharedPreferences limitations:**
  - Max ~10KB total data (practical limit, not enforced).
  - No partial updates — every `putString()` rewrites the whole file.
  - Cannot iterate entries or get all keys efficiently.
  - Slow for large amounts of data.
- **SQLCipher limitations:**
  - Native library (~8MB per ABI). APK size increases significantly (~30MB for all ABIs).
  - Opening large encrypted databases is slower than plain SQLite (5x-100x slower depending on page size and KDF iterations).
  - Passphrase must be stored securely (AndroidKeyStore). Never hardcode or derive from user input without proper stretching (pbkdf2/scrypt).
- **AndroidKeyStore:**
  - API 18 (basic), API 23 (AES/GCM), API 28 (strongBox).
  - On API 28+, use `KeyGenParameterSpec.Builder.setStrongBoxBacked(true)` for hardware-backed keys.
  - On some devices (especially older Samsung), hardware-backed keystore may be unavailable or slow.
- **File-based encryption (FBE):**
  - API 24+ (Android 7.0+).
  - Direct Boot (API 24): Device is encrypted at boot and `DeviceProtectedStorage` is available immediately.
  - Credential-encrypted storage (`context` default) is only available after user unlocks.
- **Cross-profile security:** Android 15 (API 35) introduces better per-profile isolation for enterprise apps; not relevant for typical server app.
- **Backup considerations:** Encrypted data should not be backed up to Google Drive or cloud unless you also escrow the key. Consider adding `android:allowBackup="false"` in manifest.

---

## 10. Token-Based Authentication

### Recommended Approaches

| Approach | Library | Min API | Best For |
|----------|---------|---------|----------|
| **JWT (auth tokens)** | `io.jsonwebtoken:jjwt-api:0.12.x` | API 19 | Server issuing tokens to connected clients |
| **OAuth 2.0 / OIDC (AppAuth)** | `net.openid:appauth:0.11.x` | API 16 | External identity provider (Google, GitHub) |
| **Firebase Auth** | `com.google.firebase:firebase-auth:22.x` | API 19 | Quick setup, social login, phone auth |
| **Custom token system** | HMAC-SHA256 (Javax.crypto) | API 1 | Simple, no dependency |

### JWT Library (Recommended for server-controlled auth)

```kotlin
// Gradle
implementation("io.jsonwebtoken:jjwt-api:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

// Create token
val secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret))
val token = Jwts.builder()
    .subject(clientId)
    .issuedAt(Date())
    .expiration(Date(System.currentTimeMillis() + 86400000)) // 24h
    .signWith(secretKey)
    .compact()

// Verify token
val claims = Jwts.parser()
    .verifyWith(secretKey)
    .build()
    .parseSignedClaims(token)
    .payload
```

### Token Storage (Secure)

```kotlin
// Store in EncryptedSharedPreferences (see section 9)
encryptedPrefs.edit().putString("access_token", token).apply()
encryptedPrefs.edit().putString("refresh_token", refreshToken).apply()

// For sensitive tokens, consider AndroidKeyStore-backed encryption
```

### Known Limitations

- **JWT library size:** jjwt adds ~500KB to APK. For minimal builds, implement HMAC verification manually.
- **Token expiry:** Tokens must have short expiry (15 min — 24h) and use refresh tokens.
- **Key rotation:** The signing key must be rotated periodically. Store the current key in EncryptedSharedPreferences.
- **Revocation:** JWT tokens cannot be revoked server-side unless using a blacklist (requires DB/Redis lookup on every request). For mobile server apps, blacklist via in-memory cache is acceptable for small user counts.
- **Clock skew:** JWT `exp` checks are susceptible to clock skew (±30s is typical; Jjwt allows `setAllowedClockSkewSeconds()`).
- **OAuth 2.0 / AppAuth:** The AppAuth library needs a browser or custom tab for the OAuth flow. Works within an Activity; harder to manage in a pure Service context.
- **Firebase Auth offline:** Firebase Auth caches tokens and works offline but requires internet for initial sign-in. The Firebase Auth SDK also adds ~1.5MB to APK.
- **HTTPS requirement:** Token-based auth without HTTPS exposes bearer tokens. The HTTP server MUST use TLS (self-signed cert at minimum). Use `HttpsServer` (Java) or configure NanoHTTPD with an SSL server socket.

---

## 11. Complete Manifest Permissions Reference

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.artemis_server">

    <!-- Network — Server needs to bind to ports -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

    <!-- Foreground Service — Required for persistent server -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Camera -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <!-- Audio / Microphone -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Call Recording (limited — see section 5) -->
    <uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.READ_CALL_LOG" />

    <!-- Screen Capture (user consent via system dialog) -->
    <!-- No manifest permission required for MediaProjection -->

    <!-- Location -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- Contacts -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />

    <!-- SMS -->
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.SEND_SMS" />

    <!-- Storage (scoped) -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

    <!-- Battery / Doze -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Device identifiers (optional) -->
    <uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />

    <!-- For service declarations -->
    <application ...>
        <service
            android:name=".ServerForegroundService"
            android:foregroundServiceType="dataSync|camera|microphone|location"
            android:exported="false" />
    </application>
</manifest>
```

---

## 12. Key Pitfalls & Design Recommendations

### Foreground Service Lifetime

| Android Version | Restriction | Mitigation |
|----------------|-------------|------------|
| **8+ (API 26)** | Cannot run background services | Must use foreground service with notification |
| **12+ (API 31)** | Can't launch FGS from background | Start from Activity or use `WorkManager` |
| **14+ (API 34)** | FGS types enforced | Declare ALL types your service uses |
| **14+ (API 34)** | FGS type permissions required | Add `FOREGROUND_SERVICE_*` permissions |
| **15+ (API 35)** | 6-hour FGS timeout | Restart service periodically, persist state |

### Runtime Permission Strategy

- **Batch permissions by group** — location, storage, and contacts are separate groups. Request one group at a time.
- **Handle "Don't ask again"** — On API 23+, if the user denies twice, further requests are silently ignored. Redirect to Settings.
- **One-time permissions** (API 30+) — Allowed only while app is in use; app should re-request when needed next.
- **Explain WHY** — On first launch, show a rationale screen before requesting permissions (e.g., "This app needs camera access so it can stream video to devices on your network").

### Network Considerations

- **Use high ports (8080, 8443, 9090)** — Ports < 1024 require root.
- **HTTPS / WSS is mandatory** — All traffic that carries tokens, personal data, or control commands must use TLS. Use a self-signed cert (generate on first launch with `KeyPairGenerator`) or Let's Encrypt (requires domain + port 80/443).
- **mDNS / Bonjour** — For service discovery on LAN, use `JmDNS` library (`javax.jmdns:jmdns:3.5.9`). Announce `_http._tcp.local.` and `_websocket._tcp.local.` services so clients can find the server without typing an IP.
- **Interface change** — When switching between Wi-Fi and mobile data, the server IP changes. Use a `NetworkCallback` and rebind the server socket.

### Google Play Compliance

- **Foreground service justification** — Google Play requires a clear "prominent disclosure" for foreground services. The app must explain why the service is needed and what it does.
- **MediaProjection** — Must have a legitimate use case (not general screen recording). Google Play review may reject if the purpose is unclear.
- **Call recording** — Nearly impossible to publish on Google Play. Use with extreme caution, document the limitation, and only publish in regions where it's legal.
- **SMS/Contacts access** — Requires declared use case in Play Console. Subject to review and possible rejection if not core to app functionality.
- **MANAGE_EXTERNAL_STORAGE** — Requires declaration and approval. If the server just needs to serve files, use SAF or `FileProvider` instead.
- **Target API level** — Google Play requires targeting API 33+ (as of 2025). The app must target at least API 33 or 34 to publish updates.

### Minimum Android Version Decision Matrix

| Capability | Works on API 26? | Works on API 29? | Notes |
|-----------|-----------------|-----------------|-------|
| HTTP server (FGS) | ✅ Yes (basic FGS) | ✅ Better | API 34+: service type declaration needed |
| CameraX | ❌ No (needs API 21) | ✅ Yes | But lifecycle in Service is tricky |
| Camera2 | ❌ No (needs API 21) | ✅ Yes | Works with Service context |
| Microphone recording | ✅ Yes | ✅ Yes | API 34+ needs `microphone` FGS type |
| Fused Location | ✅ Yes | ✅ Yes | API 31+ granular permissions |
| Call recording (MIC) | ✅ Yes | ⚠️ Limited | VOICE_CALL source deprecated API 29 |
| MediaProjection | ❌ No (needs API 21) | ✅ Yes | Requires Activity for consent |
| Contacts (ContentResolver) | ✅ Yes | ✅ Yes | Always dangerous permission |
| SMS (ContentResolver) | ✅ Yes | ⚠️ Limited | API 30+ needs default SMS role |
| App-specific storage | ✅ Yes | ✅ Yes | No permissions needed |
| MediaStore (scoped) | ❌ No | ✅ Yes | Scoped storage introduced API 29 |
| EncryptedSharedPreferences | ❌ No (needs API 23) | ✅ Yes | |
| SQLCipher | ✅ Yes | ✅ Yes | |
| JWT auth | ✅ Yes | ✅ Yes | |
| mDNS service discovery | ✅ Yes | ✅ Yes | JmDNS library |

### Quick-Start Gradle Dependencies Block

```kotlin
dependencies {
    // HTTP Server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Or use Java-WebSocket for WebSocket-only
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // Camera
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Encrypted Storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SQLCipher
    implementation("net.zetetic:android-database-sqlcipher:4.5.6")

    // Room (optional, with SQLCipher)
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // mDNS service discovery
    implementation("io.github.ma1uta:jmdns:3.5.9")

    // Lifecycle / Core
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
```

---

*This document was compiled from official Android developer documentation at developer.android.com, API reference pages, and current best practices as of July 2026. Android platform restrictions change yearly; always cross-reference against the latest Android 15+ behavior for foreground service timeouts and permission changes.*
