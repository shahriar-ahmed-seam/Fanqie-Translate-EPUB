# tomato-engine

Host module for the **Tomato** feature (download novels from Fanqie / 番茄小说). It runs the Rust
download engine of the open-source [Fanqie Novel Downloader](https://github.com/POf-L/Fanqie-novel-Downloader)
in a separate process (`:tomato`).

## What is in here

- **Tauri/wry Android runtime glue** under `com/pofl/fanqienoveldownloader/` and `app/tauri/`.
  These are the standard Kotlin host files that the Tauri mobile runtime expects, generated from the
  templates of:
  - `tauri` 2.12.5, `tauri-plugin-opener` 2.7.2 (Apache-2.0 OR MIT)
  - `wry` 0.57.1, `tao` 0.37.2 (Apache-2.0 OR MIT)

  The JNI functions inside the engine library are compiled for the package
  `com.pofl.fanqienoveldownloader`, so these files must keep that package name. License texts are in
  [`third_party/`](third_party/).

- **`FileOpenerPlugin` / `AccountAuthPlugin` / `NativeTtsPlugin` / `ListenMediaPlugin`** are our own
  minimal stand-ins (not the original app's code). The engine registers plugins with these class
  names at start-up and doesn't start if one is missing (engine builds from 2026-10-29 on added the
  last two); Fanqie Translate only needs lookups and downloads, so the file pickers, sharing, APK
  install, account login, read-aloud and listening controls they would normally provide are answered
  with "not available".

- **`EngineHost`, `EngineBridge`, `TomatoEngineActivity`, `TomatoEngineService`, `TomatoEngineClient`,
  `EngineLibrary`, `EngineProtocol`, `TomatoEngineProcess`** are our host: they load the engine
  library, run its invisible WebView, and expose a small `dispatch(action, payload)` IPC to the app
  process.

## The engine library is not bundled

`libfanqie_novel_downloader_tauri.so` is **not** shipped in the APK. The app downloads it once from
the upstream GitHub releases, verifies its SHA-256, and checks that it exports exactly the JNI
functions this glue calls (`TomatoEngineInstaller`). This keeps the app self-updating when Fanqie
changes its API, and keeps a large native blob out of our signed release.

## Why a separate process

A crash in the native engine only takes down `:tomato`, never the app or audiobook playback. The
next download call starts a fresh engine process.
