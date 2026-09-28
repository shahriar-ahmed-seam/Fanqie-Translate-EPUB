import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.epubtranslator.qxnrpb"
    minSdk = 24
    targetSdk = 36
    // CI passes -PversionName/-PversionCode. Local builds use the latest vX.Y.Z tag.
    // versionCode = major * 1_000_000 + minor * 1_000 + patch (same formula as .github/workflows/release.yml).
    val resolvedVersionName = providers.gradleProperty("versionName").orNull?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
      ?: latestGitTagVersion()
      ?: "1.0.0"
    versionName = resolvedVersionName
    versionCode = providers.gradleProperty("versionCode").orNull?.trim()?.toIntOrNull()
      ?: versionCodeFor(resolvedVersionName)

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Release signing comes from the environment (GitHub Actions secrets) or from a gitignored
  // keystore.properties at the project root. Nothing secret is stored in the repository.
  val signing = releaseSigning()
  signingConfigs {
    if (signing != null) {
      create("release") {
        storeFile = file(signing.getValue("storeFile"))
        storePassword = signing.getValue("storePassword")
        keyAlias = signing.getValue("keyAlias")
        keyPassword = signing.getValue("keyPassword")
      }
    }
  }

  buildTypes {
    debug {
      // With the release key available, debug builds install over release builds (handy on a real phone).
      if (signing != null) signingConfig = signingConfigs.getByName("release")
    }
    release {
      if (signing != null) signingConfig = signingConfigs.getByName("release")
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all { testTask ->
        val customCacerts = file("/home/shahriar/.jdk/jdk-21/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts")
        if (customCacerts.exists()) {
          testTask.systemProperty("javax.net.ssl.trustStore", customCacerts.absolutePath)
          testTask.systemProperty("javax.net.ssl.trustStorePassword", "changeit")
          testTask.systemProperty("javax.net.ssl.trustStoreType", "PKCS12")
        }
      }
    }
  }
  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

ksp {
  // Exported schemas are committed and used by the migration tests.
  arg("room.schemaLocation", "$projectDir/schemas")
}

/** "1.2.3" from the newest vX.Y.Z tag reachable from HEAD, or null without git/tags. */
fun latestGitTagVersion(): String? = try {
  providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*.[0-9]*.[0-9]*")
    workingDir = rootDir
    isIgnoreExitValue = true
  }.standardOutput.asText.get().trim().removePrefix("v").takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
} catch (_: Exception) {
  null
}

fun versionCodeFor(name: String): Int {
  val parts = name.substringBefore('-').split('.').map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
  val major = parts.getOrElse(0) { 1 }
  val minor = parts.getOrElse(1) { 0 }.coerceIn(0, 999)
  val patch = parts.getOrElse(2) { 0 }.coerceIn(0, 999)
  return major * 1_000_000 + minor * 1_000 + patch
}

/**
 * Release keystore settings: SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS /
 * SIGNING_KEY_PASSWORD environment variables (CI), else keystore.properties (local, gitignored).
 */
fun releaseSigning(): Map<String, String>? {
  val env = mapOf(
    "storeFile" to providers.environmentVariable("SIGNING_STORE_FILE").orNull,
    "storePassword" to providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("SIGNING_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
  )
  if (env.values.all { !it.isNullOrBlank() }) return env.mapValues { it.value!! }
  val propsFile = rootProject.file("keystore.properties")
  if (!propsFile.exists()) return null
  val props = Properties().apply { propsFile.inputStream().use { load(it) } }
  val values = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { props.getProperty(it)?.trim() }
  if (values.values.any { it.isNullOrBlank() }) return null
  val store = rootProject.file(values.getValue("storeFile")!!)
  if (!store.exists()) return null
  return values.mapValues { it.value!! } + ("storeFile" to store.absolutePath)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.media)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.jsoup)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
