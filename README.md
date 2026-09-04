# Fanqie Translate EPUB

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Fanqie Translate EPUB Logo" width="100" height="100" />
</p>

<p align="center">
  <strong>High-performance Android application for translating Chinese web novels (EPUB) into English with built-in reading, native TTS, and scalable multi-thousand chapter export.</strong>
</p>

<p align="center">
  <a href="https://github.com/shahriar-ahmed-seam/Fanqie-Translate-EPUB/releases"><img src="https://img.shields.io/github/v/release/shahriar-ahmed-seam/Fanqie-Translate-EPUB?color=blue&label=Release" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%207.0%2B%20%28API%2024%2B%29-brightgreen" alt="Platform Android 24+" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-purple" alt="Kotlin Version" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09-blueviolet" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="License" />
</p>

---

## Overview

**Fanqie Translate EPUB** is an open-source, production-ready Android application specifically designed to process, translate, and read large-scale Chinese EPUB web novels. Powered by **TomatoMTL** (with Google Translation backend), the application features a robust concurrent queue manager, constant $O(1)$ memory streaming pipelines capable of processing novels exceeding 5,000 chapters, an uninterrupted foreground service for background translation, and an integrated native reader with smart Text-to-Speech (TTS) auto-progression.

---

## Key Features

### 🚀 Scalable Translation Engine
- **Large-Novel Optimization**: $O(1)$ memory streaming architecture enables parsing, translating, and exporting massive novels (1,000 to 5,000+ chapters) without `OutOfMemoryError`.
- **TomatoMTL Integration**: Translates Chinese text into English using Google Translate web translation endpoints without requiring API keys, tokens, or external credentials.
- **Intelligent Text Chunking**: Chunks text cleanly along paragraph boundaries, protecting HTML tag trees, character limits, and Unicode integrity.

### ⚡ Concurrent Multi-Novel Queue
- **Global Worker Pool**: User-configurable concurrent worker pool (1–50 workers) shared dynamically across active novels with fair round-robin scheduling.
- **Atomic Pause & Resume**: Immediate, idempotent pause prevents new requests while letting active in-flight requests finish cleanly; resume picks up exactly where it left off.
- **Resilient Recovery**: Automatically recovers interrupted jobs on app or system restart—in-flight chunks reset safely without losing completed work.
- **Foreground Service**: Reliable background operation with interactive notification controls for real-time progress and pause/resume toggling.

### 📖 Full-Featured Reader & Native TTS
- **Modern Reading Experience**: Clean typography, font size controls, and custom themes (**System**, **Light**, **Sepia**, **Dark**).
- **Integrated Android TTS**: Built on native Android `TextToSpeech` APIs with zero external audio dependencies.
- **Smart Paragraph Progression**: Continuous speech auto-advances from paragraph to paragraph, chunk to chunk, and chapter to chapter.
- **Interactive Speech Control**: Double-tap any paragraph to speak immediately; visual highlighting follows the current spoken sentence.
- **Customizable Speech Engine**: Adjust playback speech rate and select installed voice engines dynamically.
- **Lifecycle & Background Safe**: Automatically pauses audio on app backgrounding and re-links cleanly upon foregrounding to prevent audio service crashes.

### 📦 Strict EPUB Rebuilding & Validation
- **Structure Preservation**: Maintains OPF manifests, spines, NCX / EPUB 3 Nav tables of contents, CSS stylesheets, and embedded cover art.
- **Media Preservation**: Ensures chapter illustrations (`<img>`, `<svg>`) remain completely intact during XHTML reconstruction.
- **Text Leak Prevention**: Cleans surplus source elements so untranslated Chinese text never leaks into generated English EPUBs.
- **Selective Chapter Export**: Export the full novel or select custom chapter ranges into standalone, validated EPUB files.

### 🔄 In-App Auto-Updates
- **GitHub Release Sync**: Detects newer versions directly from GitHub Releases and downloads APKs with one-tap package installation via Android's `FileProvider`.

---

## Architecture

```mermaid
graph TD
    A[Chinese EPUB Archive] -->|EpubParser (O(1) Streaming)| B[Spine & Manifest Extraction]
    B -->|EpubChunker| C[Paragraph-Bounded Chunks]
    C -->|Room Database| D[(Local SQLite Storage)]
    D <-->|TranslationQueueManager| E[TomatoMTL Engine / Google Translate]
    E -->|Foreground Service| F[Notification & Background Sync]
    D -->|ReaderScreen & ReaderTtsManager| G[Reader UI + Native TTS Engine]
    D -->|EpubRebuilder + EpubValidator| H[Validated English EPUB Export]
```

---

## Project Structure

```
app/src/main/java/com/example/
├── data/              # SQLite database (Room), entities, DAOs, and migrations
│   ├── db/            # AppDatabase, BookEntity, ChapterEntity, TranslationChunkEntity
│   └── repository/    # SettingsRepository (user preferences & persistent states)
├── epub/              # Streaming parser, chunker, rebuilder, and EPUB validator
├── queue/             # Multi-job concurrent queue, fair scheduling, and worker pool
├── service/           # Android foreground service with interactive notifications
├── translation/       # Translation provider interfaces & TomatoMTL HTTP client
├── tts/               # Android native TextToSpeech manager and lifecycle controller
├── ui/                # Jetpack Compose UI (Home, NovelDetail, Reader, Settings)
└── update/            # In-app update manager backed by GitHub Releases API
```

---

## Getting Started

### Prerequisites
- **Android Device / Emulator**: Android 7.0 (API Level 24) or higher.
- **Java Development Kit (JDK)**: JDK 21 (Temurin or OpenJDK).
- **Android SDK**: Build Tools 36, Target SDK 36.

### Build from Source

Clone the repository and build the debug APK using Gradle:

```bash
# Clone repository
git clone https://github.com/shahriar-ahmed-seam/Fanqie-Translate-EPUB.git
cd Fanqie-Translate-EPUB

# Make Gradle wrapper executable (Linux/macOS)
chmod +x ./gradlew

# Build Debug APK
./gradlew assembleDebug --no-configuration-cache
```

The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Running Automated Tests

Run the full unit test suite (Room queries, EPUB streaming, TTS lifecycle, update detection):

```bash
./gradlew testDebugUnitTest --no-configuration-cache
```

### Building with CI-Controlled Versions

Simulate CI version injection:

```bash
./gradlew assembleDebug -PversionName="1.0.8" -PversionCode=10008
```

---

## Installation & Sideloading

1. Download the latest release APK from [GitHub Releases](https://github.com/shahriar-ahmed-seam/Fanqie-Translate-EPUB/releases).
2. Transfer or open the `.apk` file on your Android device.
3. Allow **"Install unknown apps"** when prompted by your file manager or browser.
4. Open the app, grant notification permissions (for background translation), and start translating!

---

## Permissions

The app requests only standard Android permissions necessary for operation:

| Permission | Purpose |
| :--- | :--- |
| `android.permission.INTERNET` | Communicates with the TomatoMTL translation web service. |
| `android.permission.ACCESS_NETWORK_STATE` | Checks network reachability before initiating chunk requests. |
| `android.permission.FOREGROUND_SERVICE` | Translates novels continuously while the app is in the background. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ requirement for background data synchronization services. |
| `android.permission.POST_NOTIFICATIONS` | Displays interactive translation progress notifications on Android 13+. |
| `android.permission.REQUEST_INSTALL_PACKAGES` | Allows the in-app updater to install downloaded update APKs directly. |

---

## Continuous Integration & Release

Every push to `main` triggers [.github/workflows/release.yml](.github/workflows/release.yml):
1. Sets up JDK 21 and the Android SDK.
2. Calculates the next semantic version (`vX.Y.Z`) from Git tags.
3. Automatically sets `versionName` and `versionCode`.
4. Compiles and validates the debug APK and `BuildConfig.java`.
5. Publishes a new GitHub Release with the APK attached.

---

## License

This project is licensed under the [MIT License](LICENSE).
