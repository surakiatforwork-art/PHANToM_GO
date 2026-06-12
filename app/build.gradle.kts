plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("com.google.gms.google-services")
}

android {
  namespace = "com.phantom.carnavrelay"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.phantom.carnavrelay"
    minSdk = 23
    targetSdk = 34
    versionCode = 21
    versionName = "1.0.18"
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

  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
  implementation("com.google.firebase:firebase-auth")
  implementation("com.google.firebase:firebase-database")
  implementation("com.google.firebase:firebase-messaging")

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  
  // HTTP Server (NanoHTTPD)
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  
  // HTTP Client (OkHttp)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  
  // QR Code (ZXing)
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
  implementation("com.google.zxing:core:3.5.2")

  // WorkManager
  implementation("androidx.work:work-runtime-ktx:2.9.0")

  testImplementation("junit:junit:4.13.2")
}
