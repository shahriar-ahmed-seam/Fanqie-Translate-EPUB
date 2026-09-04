# Fanqie Translate EPUB

An Android application for translating Chinese web novel EPUBs into English. It combines automated machine translation, a built-in reader, background Text-to-Speech (TTS) audiobook playback, and verified EPUB export.

## Overview

Fanqie Translate EPUB streamlines reading untranslated Chinese digital publications. It accepts standard EPUB files or direct novel identifiers, extracts structural content, translates chapters through TomatoMTL without requiring external API keys, and compiles the translated text into publication-ready EPUB files or interactive reading sessions.

## Key Capabilities

- Translation Engine: Automated chapter translation via TomatoMTL with zero API keys or authentication required.
- Concurrent Queue: Multi-novel queue supporting 1 to 50 concurrent translation workers with pause, resume, and background execution.
- Background Text-to-Speech: Native Android TTS service supporting lockscreen playback, media controls, configurable playback speed (0.5x to 2.5x), custom voice selection, and continuous chapter auto-advance.
- Integrated Reader: Built-in reader supporting offline viewing, customizable typography, themes (System, Light, Dark), and synchronized reading position.
- Structure-Preserving Export: Validates and exports standard-compliant EPUBs preserving original covers, metadata, and chapter hierarchy.
- In-App Updates: Direct release checking, semantic version verification, and APK installation powered by GitHub Releases.

## Installation

Download the latest release APK from [GitHub Releases](https://github.com/shahriar-ahmed-seam/Fanqie-Translate-EPUB/releases).

1. Transfer or download the `.apk` file to your Android device.
2. Open the file to begin installation. Allow "Install unknown apps" if prompted.
3. Launch the app from the application drawer.

## System Requirements

- Operating System: Android 7.0 (API Level 24) or higher
- Target SDK: Android 16 (API Level 36)
- Storage: Sufficient storage for cached chapters and generated EPUB files
- Network: Internet connection required for translation and update checks

## Building from Source

### Prerequisites
- JDK 21
- Android SDK with Build Tools 36.0.0+

### Build Debug APK
```bash
./gradlew assembleDebug
```
The compiled APK is output to `app/build/outputs/apk/debug/`.

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

## Architecture

The project follows a layered architecture utilizing modern Android development standards:
- UI Layer: Jetpack Compose with Material 3 design system.
- Background Processing: Android Foreground Services (`TranslationService` for translation queue, `TtsPlaybackService` for media session and audio).
- Persistence: Room database for local caching of books, chapters, and translated chunks.
- Networking: OkHttp and Retrofit for HTTP client communication and update downloads.

## License

This project is licensed under the MIT License.
