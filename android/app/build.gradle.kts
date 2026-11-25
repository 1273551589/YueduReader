plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ayanami.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ayanami.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.6"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 首版不接服务器，签名用 debug key，后续换正式 key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.apply {
            // 端到端测试要真联网
            isIncludeAndroidResources = true
            all { it.jvmArgs("-Dfile.encoding=UTF-8") }
        }
    }
}

dependencies {
    // BOM 2026.03.01 会拉 lifecycle 2.11（要求 compileSdk 37），这里钉住兼容 36 的版本
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jsoup:jsoup:1.21.2")
    // Rhino：@js 规则。选 1.7.15 而不是 1.9.x —— 1.9 编译到 Java 11+ 字节码，
    // 且用了 Android 上不一定有的 java.lang.invoke 路径；1.7.15 是 Java 8 字节码。
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.google.code.gson:gson:2.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
