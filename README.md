# Manga Colorizer Android

Manga Colorizer Android is a project that runs image colorization locally on Android devices. It can colorize local images selected from your device or run real-time automatic colorization inside a WebView while browsing supported manga websites (both vertical and layout-by-page designs).

The app runs an ONNX model (`alacgan.onnx`) locally using ONNX Runtime. When available, it leverages device NPUs via QNN or NNAPI for hardware acceleration.

## Features
- **Local Manga Colorization**: Select pages or directories from your device storage to process them directly within the app's reader interface.
- **Real-Time Website Colorization (Live Streaming Mode)**: Browse your favorite manga sites. The app automatically detects images in the browser and processes them in the background, rendering them natively via a stable DOM rewrite loop. The queue will wait for new images to appear as you scroll.
- **Strict Start/Stop Flow**: The app uses an explicit state machine for its background worker. Tapping **Start Processing** initiates a session token, while tapping **Stop Processing** immediately clears the active work, invalidates the token, and resets the queue state without any zombie background processing.
- **Hardware Acceleration**: Automatically attempts to use QNN/HTP or NNAPI before falling back to CPU for high-speed local inference.
- **Deterministic Restoration**: Can deterministically restore colorized versions even after tab switches or app restores.

## Target Device Context
This project is specifically tested and targeted for the **Poco F6 (peridot)** device running an Android 17 custom ROM, prioritizing Snapdragon NPU acceleration paths (QNN).

## Model and Runtime
- Uses `alacgan.onnx` as the colorization engine.
- Relies on the `com.microsoft.onnxruntime:onnxruntime-android-qnn` library to maximize performance.
- Full support for Android 15+ 16 KB page-size memory models, ensuring native ONNX/QNN libraries load efficiently without memory faults.

## Build and Run Instructions

### Prerequisites
- Android Studio or Gradle CLI
- JDK 17
- Minimum Android SDK 26 (Target 34)

### Building
Clone the repository, open it in Android Studio, and click **Build -> Make Project**, or run:
```bash
./gradlew assembleDebug
```

### Installation
```bash
./gradlew installDebug
```

## Permission Requirements
The app requests the following permissions:
- `INTERNET`: For browsing online manga inside the WebView.
- `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`: To pick local manga files.
- `MANAGE_EXTERNAL_STORAGE`: Needed on some devices for broad directory access.
- `POST_NOTIFICATIONS`: To show background progress when processing many images.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: Keeps the model running when the app goes to the background.

## Logging and Debugging
- All significant events (queue states, token validation mismatches, hard stops, explicit START/STOP events, cache hits/misses, inference timing, model load events) are logged.
- The app automatically outputs logs to `/sdcard/MangaColorizer/session_logs.txt` if storage permission is granted, otherwise it falls back to app-specific external storage (`Android/data/.../files/logs/`).

## Compatibility Notes
- **16 KB Page-Size Support**: Native libraries (ONNX Runtime, QNN) are explicitly uncompressed inside the APK by configuring `android:extractNativeLibs="false"`, meeting the 16 KB alignment requirements for Android 15/17 custom ROM devices.

## Troubleshooting
- **Colorization is slow**: Ensure the ONNX QNN backend is functioning. Check the logs for `AI: QNN HTP Backend enabled`. If missing, it will fallback to NNAPI or CPU which is significantly slower.
- **Images revert when switching tabs**: Verify the app background service isn't being killed by aggressive battery management. The UI automatically handles JS framework-induced DOM reverts by explicitly checking `data-processed` flags against actual `src` tags.

## Contributing
Contributions are welcome. Please ensure that the `ProcessingState` single source of truth is respected when modifying the queue or inference lifecycle, and test both Local and Browser workflows on a hardware device if making changes to the ONNX pipeline.
