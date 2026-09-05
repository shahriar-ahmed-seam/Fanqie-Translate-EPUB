import java.util.Base64
import java.util.concurrent.TimeUnit

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
    fun resolveVersionName(): String {
      val prop = providers.gradleProperty("versionName").orNull
        ?: project.findProperty("versionName")?.toString()
      if (!prop.isNullOrBlank()) return prop.trim()

      return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
          .directory(rootDir)
          .redirectOutput(ProcessBuilder.Redirect.PIPE)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
        val tag = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(2, TimeUnit.SECONDS)
        if (tag.isNotEmpty()) {
          tag.removePrefix("v").removePrefix("V").trim()
        } else {
          "1.0.0"
        }
      } catch (_: Exception) {
        "1.0.0"
      }
    }

    fun resolveVersionCode(): Int {
      val prop = providers.gradleProperty("versionCode").orNull
        ?: project.findProperty("versionCode")?.toString()
      prop?.toIntOrNull()?.let { return it }

      return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
          .directory(rootDir)
          .redirectOutput(ProcessBuilder.Redirect.PIPE)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
        val countStr = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(2, TimeUnit.SECONDS)
        countStr.toIntOrNull()?.takeIf { it > 0 } ?: 1
      } catch (_: Exception) {
        1
      }
    }

    versionCode = resolveVersionCode()
    versionName = resolveVersionName()

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    getByName("debug") {
      val defaultKeystore = file("${rootDir}/debug.keystore")
      if (!defaultKeystore.exists()) {
        val base64File = file("${rootDir}/debug.keystore.base64")
        if (base64File.exists()) {
          val decoded = Base64.getDecoder().decode(base64File.readText().trim())
          defaultKeystore.writeBytes(decoded)
        }
      }
      if (defaultKeystore.exists()) {
        storeFile = defaultKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    debug {
      signingConfig = signingConfigs.getByName("debug")
    }
    release {
      signingConfig = signingConfigs.getByName("debug")
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
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
