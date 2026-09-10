plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.crossclip.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.crossclip.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "1.1.1"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // OkHttp 用于 WebSocket 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Java-WebSocket 用于手机端本地 WebSocket 服务端 (绕过 Windows 防火墙拦截)
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // Shizuku 核心 API 与 Provider
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // LSPosed HiddenApiBypass: 绕过 Android 10+ 对隐藏 AIDL 接口的反射限制
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
