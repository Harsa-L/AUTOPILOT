plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.autopilot.drone.protocolapi"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(project(":core:common"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}
