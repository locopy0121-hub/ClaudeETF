plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions 的執行編號當作版本號，每次建置自動 +1
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.claudeetf.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudeetf.app"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        // 顯示版本 0.2.修訂號（修訂號由 workflow 計算：建置編號 − 68），versionCode 仍用建置編號確保可覆蓋更新
        versionName = "0.2." + (System.getenv("PATCH") ?: (runNumber - 68).coerceAtLeast(0).toString()) + "-dev"
    }

    // 固定簽章：每次建置的 APK 都能直接覆蓋安裝更新
    signingConfigs {
        create("release") {
            storeFile = file("claudeetf.keystore")
            storePassword = "claudeetf"
            keyAlias = "claudeetf"
            keyPassword = "claudeetf"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 群聊 P2P：Google Nearby Connections（藍牙／Wi-Fi 直連，無伺服器）
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Play 服務帶入舊版 fragment，指定新版讓 ActivityResult 權限請求正常
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // 跨網 P2P：WebRTC DataChannel（Google libwebrtc 打包版）
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    // 邀請碼 QR Code：產生（zxing core）與掃描（相機）
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
