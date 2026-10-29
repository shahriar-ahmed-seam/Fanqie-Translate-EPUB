package com.example.tomato

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.diagnostics.AppDiagnostics
import com.example.tomato.engine.EngineLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** One downloadable engine build (an upstream Android release). */
data class EngineRelease(
    val tag: String,
    val assets: Map<String, EngineAsset>
)

data class EngineAsset(
    val name: String,
    val url: String,
    val size: Long,
    /** Lowercase hex SHA-256 of the APK. */
    val sha256: String?
)

/** The engine on this phone. */
data class InstalledEngine(
    val tag: String,
    val abi: String,
    val path: String,
    val librarySha256: String,
    val installedAt: Long
) {
    /** "r760 · 2026-10-25" from "unsigned-v2026.10.25-1124-r760". */
    val label: String get() = TomatoEngineInstaller.describeTag(tag)
}

/** Why an engine can't be installed (readable, shown in the Tomato tab). */
class EngineInstallException(message: String) : IOException(message)

/**
 * Gets the Tomato engine onto the phone. The engine is the Rust core of the Fanqie Novel Downloader
 * (github.com/POf-L/Fanqie-novel-Downloader); it isn't bundled with the app. The installer downloads
 * the upstream Android build for this phone's CPU, checks its SHA-256, extracts only the engine
 * library, and checks that the library exports exactly the native functions this app's runtime glue
 * expects (a newer upstream Tauri version would otherwise crash the engine on start).
 */
class TomatoEngineInstaller(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val root = File(context.filesDir, "tomato_engine")
    private val stateFile = File(root, "state.json")

    fun deviceAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }

    fun installed(): InstalledEngine? {
        val json = try {
            if (!stateFile.exists()) return null
            JSONObject(stateFile.readText()).optJSONObject("current") ?: return null
        } catch (e: Exception) {
            return null
        }
        val engine = InstalledEngine(
            tag = json.optString("tag"),
            abi = json.optString("abi"),
            path = json.optString("path"),
            librarySha256 = json.optString("librarySha256"),
            installedAt = json.optLong("installedAt")
        )
        return engine.takeIf { it.tag.isNotBlank() && File(it.path).isFile }
    }

    /**
     * Downloads [release] for this phone and prepares it. Returns the engine but doesn't make it
     * current: call [makeCurrent] after it passed a start-up check.
     */
    suspend fun download(release: EngineRelease, onProgress: (Float?) -> Unit): InstalledEngine = withContext(Dispatchers.IO) {
        val abi = deviceAbi() ?: throw EngineInstallException(
            "The Tomato engine isn't available for this phone's processor (${Build.SUPPORTED_ABIS.joinToString()})."
        )
        val asset = release.assets[abi] ?: throw EngineInstallException("This engine release has no build for $abi.")
        val downloads = File(context.cacheDir, "tomato_engine_download").apply { mkdirs() }
        val apk = File(downloads, "engine-$abi.apk")
        try {
            downloadVerified(asset, apk, onProgress)
            extract(release.tag, abi, apk, apk.sha256Hex())
        } finally {
            apk.delete()
        }
    }

    /** Installs from an already downloaded upstream APK (tests and manual installs). */
    suspend fun installFromApk(tag: String, apk: File): InstalledEngine = withContext(Dispatchers.IO) {
        val abi = deviceAbi() ?: throw EngineInstallException("The Tomato engine isn't available for this phone's processor.")
        extract(tag, abi, apk, apk.sha256Hex())
    }

    /**
     * Uses [engine] from now on. With [deletePrevious] the engine used before is deleted; an update
     * keeps it until the new one has started (and goes back to it if it doesn't). Deleting it right
     * away used to leave no engine at all after a failed update.
     */
    fun makeCurrent(engine: InstalledEngine, deletePrevious: Boolean = true) {
        val previous = installed()
        root.mkdirs()
        val json = JSONObject().put(
            "current",
            JSONObject()
                .put("tag", engine.tag)
                .put("abi", engine.abi)
                .put("path", engine.path)
                .put("librarySha256", engine.librarySha256)
                .put("installedAt", engine.installedAt)
        )
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(json.toString())
            tmp.delete()
        }
        // Keep only the current engine on disk.
        if (deletePrevious && previous != null && previous.path != engine.path) deleteEngineFiles(previous)
        AppDiagnostics.log("tomato", "Engine ${engine.label} (${engine.abi}) is now in use")
    }

    fun discard(engine: InstalledEngine) {
        if (installed()?.path == engine.path) return
        deleteEngineFiles(engine)
    }

    /** Removes the engine and its working data (downloaded books are kept by the repository). */
    fun uninstall() {
        stateFile.delete()
        root.deleteRecursively()
    }

    /** Newest upstream release (GitHub), with its Android assets. */
    suspend fun latestRelease(): EngineRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$UPSTREAM_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EngineInstallException("Couldn't check for engine updates (HTTP ${response.code}).")
            parseRelease(JSONObject(response.body?.string().orEmpty()))
        }
    }

    private fun downloadVerified(asset: EngineAsset, target: File, onProgress: (Float?) -> Unit) {
        val expected = asset.sha256 ?: throw EngineInstallException("This engine build has no checksum, so it can't be verified.")
        val request = Request.Builder().url(asset.url).header("User-Agent", USER_AGENT).build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EngineInstallException("Engine download failed (HTTP ${response.code}).")
            val body = response.body ?: throw EngineInstallException("Engine download failed (empty response).")
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        } else {
                            onProgress(null)
                        }
                    }
                }
            }
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(expected, ignoreCase = true)) {
            target.delete()
            throw EngineInstallException("The engine download is damaged (checksum mismatch). Please try again.")
        }
    }

    private suspend fun extract(tag: String, abi: String, apk: File, apkSha256: String): InstalledEngine {
        val dir = File(root, "engines/${safeName(tag)}/$abi").apply { mkdirs() }
        val target = File(dir, EngineLibrary.SO_FILE_NAME)
        val staging = File(dir, EngineLibrary.SO_FILE_NAME + ".tmp")
        try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/$abi/${EngineLibrary.SO_FILE_NAME}")
                    ?: throw EngineInstallException("The engine package has no library for $abi.")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(staging).use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        } catch (e: IOException) {
            staging.delete()
            if (e is EngineInstallException) throw e
            throw EngineInstallException("The engine package couldn't be read: ${e.message}")
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        checkCompatible(staging)
        if (target.exists()) {
            target.setWritable(true)
            target.delete()
        }
        if (!staging.renameTo(target)) throw EngineInstallException("Couldn't save the engine.")
        // Loaded code must not be writable (Android 14+ refuses writable dynamically loaded code).
        target.setReadOnly()
        Log.i(TAG, "Engine $tag ($abi) extracted from APK $apkSha256")
        return InstalledEngine(
            tag = tag,
            abi = abi,
            path = target.absolutePath,
            librarySha256 = target.sha256Hex(),
            installedAt = System.currentTimeMillis()
        )
    }

    private fun checkCompatible(library: File) {
        val names = ElfSymbols.dynamicSymbolNames(library)
            ?: throw EngineInstallException("The engine library isn't valid for this phone.")
        val missing = REQUIRED_NATIVE_SYMBOLS.filterNot { it in names }
        if (missing.isNotEmpty()) {
            library.delete()
            AppDiagnostics.log("tomato", "Engine rejected: missing ${missing.size} functions, e.g. ${missing.first()}")
            throw EngineInstallException(
                "This engine version was built with a newer runtime than this app supports. " +
                    "Keep using the current engine, or update Fanqie Translate."
            )
        }
    }

    private fun deleteEngineFiles(engine: InstalledEngine) {
        try {
            File(engine.path).parentFile?.parentFile?.let { tagDir ->
                tagDir.walkBottomUp().forEach {
                    it.setWritable(true)
                    it.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete engine ${engine.tag}", e)
        }
    }

    companion object {
        private const val TAG = "TomatoEngineInstaller"
        const val UPSTREAM_REPO = "POf-L/Fanqie-novel-Downloader"
        const val UPSTREAM_URL = "https://github.com/$UPSTREAM_REPO"
        private const val USER_AGENT = "Fanqie-Translate-Android"
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /** Native functions the runtime glue in :tomato-engine (wry 0.57.1, tauri 2.12.5) calls. */
        val REQUIRED_NATIVE_SYMBOLS = listOf(
            "Java_app_tauri_plugin_PluginManager_handlePluginResponse",
            "Java_app_tauri_plugin_PluginManager_sendChannelData",
            "Java_com_pofl_fanqienoveldownloader_Rust_assetLoaderDomain",
            "Java_com_pofl_fanqienoveldownloader_Rust_handleReceivedTitle",
            "Java_com_pofl_fanqienoveldownloader_Rust_handleRequest",
            "Java_com_pofl_fanqienoveldownloader_Rust_ipc",
            "Java_com_pofl_fanqienoveldownloader_Rust_onCreate",
            "Java_com_pofl_fanqienoveldownloader_Rust_onDestroy",
            "Java_com_pofl_fanqienoveldownloader_Rust_onEval",
            "Java_com_pofl_fanqienoveldownloader_Rust_onFirstActivityCreate",
            "Java_com_pofl_fanqienoveldownloader_Rust_onFirstActivityCreateWry",
            "Java_com_pofl_fanqienoveldownloader_Rust_onLowMemory",
            "Java_com_pofl_fanqienoveldownloader_Rust_onNewIntent",
            "Java_com_pofl_fanqienoveldownloader_Rust_onPageLoaded",
            "Java_com_pofl_fanqienoveldownloader_Rust_onPageLoading",
            "Java_com_pofl_fanqienoveldownloader_Rust_onPause",
            "Java_com_pofl_fanqienoveldownloader_Rust_onResume",
            "Java_com_pofl_fanqienoveldownloader_Rust_onStart",
            "Java_com_pofl_fanqienoveldownloader_Rust_onStop",
            "Java_com_pofl_fanqienoveldownloader_Rust_onWebviewDestroy",
            "Java_com_pofl_fanqienoveldownloader_Rust_onWindowFocusChanged",
            "Java_com_pofl_fanqienoveldownloader_Rust_shouldOverride",
            "Java_com_pofl_fanqienoveldownloader_RustWebChromeClient_onGeolocationPermissionRequestNative",
            "Java_com_pofl_fanqienoveldownloader_RustWebChromeClient_onPermissionRequestNative"
        )

        private const val PINNED_TAG = "unsigned-v2026.10.29-1422-r774"
        private const val PINNED_VERSION = "2026.10.29-1422"

        /** The engine build this app version was tested with (used for first installs). */
        val PINNED_RELEASE = EngineRelease(
            tag = PINNED_TAG,
            assets = mapOf(
                "arm64-v8a" to pinnedAsset("arm64-v8a", 20_916_698, "1726f6ad8b751e49c4b579874c3ca9e8bbe60c4d91470b1ce92436923676e9b3"),
                "armeabi-v7a" to pinnedAsset("armeabi-v7a", 16_623_480, "5f26917a61f5fd722178cb440c44d992eff8fd65119783489da97393c931638b"),
                "x86_64" to pinnedAsset("x86_64", 23_003_497, "8814901fd35d46f6dd9eed6587e9c8e53b18a016f0cd85d8816ecb12274bca4a")
            )
        )

        private fun pinnedAsset(abi: String, size: Long, sha256: String): EngineAsset {
            val name = "FanqieNovelDownloader-$PINNED_VERSION-android-$abi.apk"
            return EngineAsset(name, "$UPSTREAM_URL/releases/download/$PINNED_TAG/$name", size, sha256)
        }

        internal fun parseRelease(json: JSONObject): EngineRelease {
            val tag = json.optString("tag_name")
            if (tag.isBlank()) throw EngineInstallException("The engine release has no version.")
            val assets = LinkedHashMap<String, EngineAsset>()
            val list = json.optJSONArray("assets")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val abi = SUPPORTED_ABIS.firstOrNull { name.endsWith("-android-$it.apk", ignoreCase = true) } ?: continue
                    assets[abi] = EngineAsset(
                        name = name,
                        url = item.optString("browser_download_url"),
                        size = item.optLong("size"),
                        sha256 = com.example.update.AppUpdateManager.parseSha256Digest(item.optString("digest"))
                    )
                }
            }
            return EngineRelease(tag, assets)
        }

        /** Short readable form of an upstream tag. */
        fun describeTag(tag: String): String {
            val match = Regex("""v(\d{4})\.(\d{1,2})\.(\d{1,2})[^r]*(r\d+)?""").find(tag) ?: return tag
            val (year, month, day, run) = match.destructured
            val date = "%s-%02d-%02d".format(year, month.toInt(), day.toInt())
            return if (run.isNotBlank()) "$run · $date" else date
        }

        /** Newer upstream build than [current]? Tags carry the build date and a run number. */
        fun isNewerTag(candidate: String, current: String): Boolean {
            fun key(tag: String): List<Long> {
                val m = Regex("""v(\d{4})\.(\d{1,2})\.(\d{1,2})-(\d+)(?:-r(\d+))?""").find(tag) ?: return emptyList()
                return m.groupValues.drop(1).map { it.toLongOrNull() ?: 0L }
            }
            val a = key(candidate)
            val b = key(current)
            if (a.isEmpty() || b.isEmpty()) return candidate != current
            for (i in a.indices) {
                if (a[i] != b.getOrElse(i) { 0 }) return a[i] > b.getOrElse(i) { 0 }
            }
            return false
        }

        private fun safeName(tag: String) = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        internal fun File.sha256Hex(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }
    }
}
