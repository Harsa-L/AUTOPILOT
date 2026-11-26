plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.autopilot.nativecore"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  ndkVersion = "29.0.14206865"

  defaultConfig {
    minSdk = 26

    externalNativeBuild {
      cmake {
        cppFlags("-std=c++20 -O2 -Wall -Wextra")
        arguments("-DANDROID_STL=c++_shared")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.31.6"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(project(":core:common"))
  implementation(project(":drone:protocol-api"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.junit)
}
