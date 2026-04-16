plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.billsnap.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.billsnap.manager"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Only package ARM ABIs for clean native lib packaging
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 16KB page size alignment — AGP 8.5.2 auto-aligns when useLegacyPackaging = false
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // CameraX (1.4.1 — ships 16KB-aligned native libraries)
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Guava — required to resolve ListenableFuture classpath conflict between CameraX and Firebase
    implementation("com.google.guava:guava:33.0.0-android")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Glide (image loading)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    
    // Coil (Image loading + Modern caching)
    implementation("io.coil-kt:coil:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ML Kit Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PaddleOCR4Android (PP-OCRv4 Lite wrapper)
    implementation("com.github.equationl.paddleocr4android:paddleocr4android:v1.2.9")

    // Biometric (App Lock)
    implementation("androidx.biometric:biometric:1.1.0")

    // EncryptedSharedPreferences (PIN storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase BOM (manages all Firebase versions)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Google Sign-In (Credential Manager — replaces deprecated play-services-auth)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Legacy Google Sign-In — more reliable than Credential Manager on MIUI/custom ROMs
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Google Drive API (replaces Firebase Storage — no Blaze plan required)
    implementation("com.google.api-client:google-api-client-android:2.7.2") {
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "io.grpc")  // prevent gRPC version conflict with Firebase
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240521-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "io.grpc")
    }
    implementation("com.google.http-client:google-http-client-gson:1.45.3") {
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "io.grpc")
    }

    // Force gRPC version compatible with Firebase Firestore (fixes InternalGlobalInterceptors crash)
    implementation("io.grpc:grpc-api:1.62.2")

    // Retrofit (Multi-Currency Support)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
}
