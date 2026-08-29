# Fanqie Translate EPUB

An Android application for translating Chinese EPUB web novels into English while preserving document structure, reading order, and styling.

## Overview

Fanqie Translate EPUB is designed to parse Chinese EPUB files, extract their metadata and chapter content, translate the text into English using TomatoMTL, and export a fully formed English EPUB. The original document structure—including OPF manifests, spines, navigation tables, stylesheets, covers, and embedded resources—is preserved throughout the translation and reconstruction pipeline.

## Features

- **EPUB Import**: Import single or multiple Chinese EPUB files via the Android Storage Access Framework (SAF).
- **Metadata and Chapter Translation**: Translates novel titles, descriptions, chapter headings, and chapter body content.
- **Concurrent Worker Pool**: Configurable translation concurrency with bounded task workers and automatic rate-limit backoff.
- **Translation Queue**: Manage multiple books with individual job tracking, queue prioritization, and persistent progress.
- **Pause and Resume**: Pause active translation jobs and resume them across app restarts without re-translating completed chunks.
- **Foreground Service**: Background translation execution with persistent notification controls and progress updates.
- **Progress Tracking**: Real-time visibility into overall progress, completed chunks, active workers, and per-chapter translation state.
- **Cover and Resource Preservation**: Retains original covers, images, and CSS formatting in the generated English EPUB.
- **Full EPUB Export**: Exports validated English EPUB files directly to device storage.
- **Range and Chapter Selection Export**: Export custom subsets of translated chapters or specific chapter ranges into standalone EPUB files.
- **Local Persistence**: Stores translated content locally using Room database to enable offline reading and incremental exports.
- **Built-in Reader**: Built-in chapter reader with customizable typography, themes, navigation, and Table of Contents drawer.
- **Large Novel Support**: Grouped chapter indexing and streaming rebuild pipelines optimized for multi-hundred and multi-thousand chapter novels.

## Translation

The application communicates with the TomatoMTL translation engine using its web translation mechanism with Google Translation selected as the translation provider. Source text is chunked at paragraph boundaries to respect provider character limits while preventing HTML tag corruption and malformed Unicode sequences.

The application does not require API keys or external account credentials.

## EPUB Handling

The EPUB pipeline parses the container definition (`META-INF/container.xml`), locates the package document (`.opf`), and follows the spine to guarantee exact chapter reading order. During export:

- All original spine items and manifest references are resolved.
- Translated XHTML content is validated for markup correctness.
- Navigation documents (NCX and EPUB 3 Navigation Documents) are updated with translated titles.
- Output archive integrity is verified prior to saving.

## Large Novels

To accommodate extensive web novels containing hundreds or thousands of chapters, the application uses persistent on-device SQLite storage (via Room) and chunked processing. Rather than buffering complete novel contents in memory, chapters and translation chunks are loaded and written incrementally, maintaining stable memory usage during processing and export.

## Build

The project uses Gradle with the Kotlin DSL. To build a debug APK locally, run:

```bash
./gradlew assembleDebug
```

For release builds with custom version properties:

```bash
./gradlew assembleDebug -PversionName="1.0.0" -PversionCode=1
```

## GitHub Actions

The repository includes a continuous integration and release workflow (`.github/workflows/release.yml`) that executes on pushes to `main` or `master`. The workflow:

1. Sets up JDK 17 and the Android SDK environment.
2. Automatically determines the next semver tag and monotonically increasing version code.
3. Builds the APK without requiring manual intervention or PC setup.
4. Creates a Git tag and publishes a GitHub Release with the compiled APK attached.

## Installation

1. Navigate to the **Releases** section of this GitHub repository.
2. Download the latest `.apk` asset.
3. Open the downloaded file on your Android device and proceed with installation (enable "Install unknown apps" if prompted).

## Project Structure

```
app/src/main/java/com/example/
├── data/           # Room database, DAOs, entities, and type converters
├── epub/           # EPUB parser, chunker, rebuilder, validator, and model definitions
├── queue/          # Translation task queue, concurrency control, and job management
├── service/        # Android foreground service for background translation
├── translation/    # Translation provider interface and TomatoMTL client implementation
├── ui/             # Jetpack Compose UI screens, components, viewmodels, and theme
└── update/         # In-app update checker utilizing GitHub Releases API
```

## Development

- **IDE**: Android Studio Ladybug (or newer) or standard Gradle CLI.
- **JDK**: Java 17 (Temurin recommended).
- **Minimum SDK**: Android API 26 (Android 8.0).
- **Target SDK**: Android API 35 (Android 15).

To import the project into Android Studio, open the root directory and allow Gradle to sync project dependencies.

## License

No explicit license is currently provided for this project. All rights are reserved by the repository owner unless otherwise specified.
