plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.phantom.dashcam"
  compileSdk = 35

  defaultConfig {
    minSdk = 23
    consumerProguardFiles("consumer-rules.pro")
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

  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    viewBinding = true
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.activity:activity-ktx:1.9.2")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
  implementation("androidx.lifecycle:lifecycle-service:2.8.4")
  implementation("androidx.lifecycle:lifecycle-process:2.8.4")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

  implementation("androidx.camera:camera-core:1.5.1")
  implementation("androidx.camera:camera-camera2:1.5.1")
  implementation("androidx.camera:camera-lifecycle:1.5.1")
  implementation("androidx.camera:camera-video:1.5.1")
  implementation("androidx.camera:camera-view:1.5.1")
  implementation("androidx.camera:camera-effects:1.5.1")
  implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")

  // Software video stabilization (post-process, for devices without hardware EIS/OIS).
  // 4.13.0 ships 16 KB-page-aligned native libs (libopencv_java4.so + libc++_shared.so) so it
  // does not trip the Android 15 "not 16 KB compatible" warning that 4.11.0 / 5.0.0 do.
  implementation("org.opencv:opencv:4.13.0")

  testImplementation("junit:junit:4.13.2")
}
