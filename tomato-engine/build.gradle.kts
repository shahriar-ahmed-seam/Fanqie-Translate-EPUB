// Host for the Tomato engine (Fanqie Novel Downloader's Rust core), run in its own process.
// Contains the Tauri Android runtime glue (tauri 2.12.5, wry 0.57.1, tauri-plugin-opener 2.7.2;
// Apache-2.0 OR MIT, see third_party/) generated for package com.pofl.fanqienoveldownloader, which is
// the package the engine library's JNI functions are compiled for. The engine library itself is not
// bundled: the app downloads it from the upstream releases and verifies it.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.example.tomato.engine"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("androidx.activity:activity-ktx:1.10.1")
  implementation("androidx.webkit:webkit:1.14.0")
  implementation("androidx.browser:browser:1.8.0")
  implementation("androidx.lifecycle:lifecycle-process:2.8.7")
  implementation("androidx.startup:startup-runtime:1.2.0")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
  // org.json is part of Android itself.
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-json-org:2.15.3") {
    exclude(group = "org.json", module = "json")
  }
}
