plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tencent.mm.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tencent.mm.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
        // 说明：UnitTest 使用 JVM 上的 org.json 实现（与 Android 端 API 兼容），
        // 用于离线校验「过滤"通用取数/分片"逻辑」，避免任何回归。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // AGENTS.md 铁律 6：固定仓库内测试签名，保证云端 CI 产物可无损 install -r 覆盖。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-ci.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        viewBinding = false
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // WP-28：真机 Instrumentation 模拟点击（仅 androidx.test 官方测试库，无网络/注入第三方）。
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
