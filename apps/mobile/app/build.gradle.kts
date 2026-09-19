plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "app.nester"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.nester"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2025.10.00"))
  implementation("androidx.activity:activity-compose:1.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("com.squareup.okhttp3:okhttp:5.1.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

  testImplementation("junit:junit:4.13.2")
}
