plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.local.enhancer"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.local.enhancer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // 只打包 64 位元 ARM（近年手機都是），APK 從約 120MB 降到約 45MB；原版也只有 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("fixed") {
            // 固定簽章：每次 Actions 編出來的 APK 都能直接覆蓋安裝
            storeFile = file("enhancer.keystore")
            storePassword = "enhancer123"
            keyAlias = "enhancer"
            keyPassword = "enhancer123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // Shizuku（免 root 取得 adb shell 權限）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 呼叫系統隱藏 API（畫圈搜尋）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // 穿透式翻譯：ML Kit 離線翻譯、語言偵測、OCR
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
