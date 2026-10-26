package com.example.tomato.engine

import androidx.startup.InitializationProvider

/**
 * androidx.startup's provider only runs in the main process. The engine's Tauri runtime follows
 * ProcessLifecycleOwner, which must be started by a provider, so the engine process gets its own
 * (declared with android:process=":tomato" in the manifest).
 */
class TomatoEngineStartupProvider : InitializationProvider()
