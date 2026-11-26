plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.autopilot.vision"
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
  implementation(project(":native:core"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.litert)
  implementation(libs.litert.gpu)
  testImplementation(libs.junit)
}
