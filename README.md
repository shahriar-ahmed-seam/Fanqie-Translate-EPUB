# Fanqie Translate EPUB

An Android application that translates Chinese web novel EPUBs into English, featuring a built-in reader, native Text-to-Speech (TTS), and background queue processing.

## Features

- **Automated EPUB Translation**: Translates Chinese EPUB novels to English using TomatoMTL without requiring API keys or account setup.
- **High-Capacity Processing**: Streams and processes large novels with thousands of chapters with stable, low-memory performance.
- **Concurrent Translation Queue**: Configurable worker pool (1 to 50 workers) with pause, resume, and background service support.
- **Built-in Reader**: Offline reading with customizable font sizing and themes (System, Light, Sepia, Dark).
- **Native Text-to-Speech**: Integrated Android TTS with paragraph highlighting, double-tap playback, automatic progression, and voice/speed controls.
- **Validated EPUB Export**: Exports standard-compliant English EPUBs preserving covers, table of contents, and embedded illustrations.
- **Automatic Updates**: Built-in update checker and installer integrated with GitHub Releases.

## Installation

Download the latest APK from the [GitHub Releases](https://github.com/shahriar-ahmed-seam/Fanqie-Translate-EPUB/releases) page and install it on your Android device (Android 7.0+).

## Building from Source

### Prerequisites
- JDK 21
- Android SDK (API Level 36)

### Build Debug APK
```bash
./gradlew assembleDebug
```

The output APK will be placed in `app/build/outputs/apk/debug/`.

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

## Requirements

- **Minimum Version**: Android 7.0 (API Level 24)
- **Target Version**: Android 16 (API Level 36)

## License

This project is licensed under the MIT License.
