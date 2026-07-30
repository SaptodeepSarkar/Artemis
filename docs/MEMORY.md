# Developer Memory & Context

## Critical Platform Gotchas

### API 35+ (Android 16): 6-Hour FGS Timeout
Android 16 (API 35+) introduces a hard 6-hour timeout on ALL foreground services. After 6 hours, the system kills the service regardless of type. **Mitigation:** WorkManager periodic task (every 5 hours) that checks if service is running, restarts if not. On API 34 and below, declare all service types but the timeout doesn't apply. This is our biggest long-term risk for 24/7 operation.

### API 29+ (Android 10): VOICE_CALL Source Deprecated
The `MediaRecorder.AudioSource.VOICE_CALL` source returns silence on Android 10+ (API 29). Call recording via standard API is effectively **broken**. The `CAPTURE_AUDIO_OUTPUT` permission is system/signature-only and not available to third-party apps. Only option: AccessibilityService-based call detection + speakerphone mic capture (degraded). Document this limitation clearly.

### API 34+ (Android 14): Foreground Service Types
Must declare ALL applicable types in manifest:
`android:foregroundServiceType="dataSync|camera|microphone|location"`
Each type requires a matching `FOREGROUND_SERVICE_*` permission.
If you forget any, the service crashes silently with SecurityException.

### SQLCipher APK Size
SQLCipher adds ~30MB to APK size (native .so files for multiple ABIs). Consider using `android-database-sqlcipher` with ABI splits or enabling `android.bundle.enableUncompressedNativeLibs=false` for Play Store distribution.

### CameraX in Service Context
CameraX requires a `LifecycleOwner`. In a pure Service (no Activity), use `ProcessLifecycleOwner.get()` from `androidx.lifecycle:lifecycle-process`. However, CameraX still prefers an Activity context. Alternative: Use Camera2 directly with manual `CameraDevice` and `CameraCaptureSession` management — more code but works reliably in Service context.

## Environment
- **Host OS:** Pop!_OS 24.04 (Linux 7.0.11)
- **User:** saptodeepsarkar
- **Project root:** /home/saptodeepsarkar/Projects/Artemis
- **Python:** python3 (3.12.3)
- **No Android SDK installed yet** — will need `sdkmanager` or Android Studio

## Project Name
- **App name:** Artemis Android Sentinel
- **Package:** com.artemis.sentinel
- **Internal codename:** agent-sentinel

## Key Decisions Made

### Why Ktor over NanoHTTPD
Ktor is Kotlin-native, coroutine-based, supports WebSocket and TLS natively, and has a tiny footprint. NanoHTTPD would require manual threading and has no built-in WebSocket support. Ktor's engine-based architecture (CIO/Netty) works well on Android.

### Why SQLCipher over Room + encryption
Room doesn't natively support encrypted databases easily. SQLCipher provides transparent AES-256 encryption at the DB layer. We can still use Room as an ORM on top of SQLCipher's SQLiteOpenHelper.

### Why CameraX over Camera2
CameraX handles lifecycle, device compatibility, and rotation automatically. Falls back to Camera2 for advanced features (manual focus, raw capture).

### Protocol choice
HTTP REST for simple request/response operations (location query, contacts, etc.) + WebSocket for streaming (camera, mic, screen) + WebSocket for event stream. gRPC considered but adds complexity w.r.t. TLS and protobuf dependency size.

## OEM Survival Notes
- **Xiaomi:** Must add app to autostart list, disable "Pause app activity" in recent apps
- **Huawei/Honor:** "Protected apps" setting, disable "Close after screen lock"
- **Samsung:** Disable "Put unused apps to sleep" in Device Care
- **OPPO:** Disable "Freeze app" and "Deep freeze" in Security Center
- **OnePlus:** Disable "Optimize battery" for the app
- **Google Pixel:** Generally well-behaved; just need to disable battery optimization

## Legal Requirements
- Call recording: User must check local laws. App must display explicit consent notice.
- AccessibilityService: Must be used only for approved purpose (remote control). Google Play rejects apps that use AccessibilityService for remote control without clear user benefit.
- Since this app is self-hosted (not on Play Store), Play Store restrictions don't apply, but local laws still do.

## Future Considerations
- v2.0: Remote relay server for internet access (WebRTC/TURN)
- v2.0: Desktop dashboard application (Electron/Tauri)
- v2.0: End-to-end encryption (E2EE) for all data
- v3.0: Root-based features (full call recording, app data access)
