plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.hellocompose"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.example.hellocompose"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 识别逻辑是纯 JVM 代码，单元测试里直接连真实站点验证，输出打到控制台
    testOptions {
        unitTests.all {
            // -DliveSite=... 默认只设到 Gradle 守护进程，fork 出来的测试 JVM 读不到，
            // 必须显式透传；否则实连测试永远走「跳过」，看起来是通过了
            it.systemProperty("liveSite", System.getProperty("liveSite") ?: "")
            it.testLogging {
                showStandardStreams = true
                events("passed", "failed", "skipped")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    // 导航与本地设置存储
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // 网页抓取与图片加载
    implementation(libs.jsoup)
    implementation(libs.coil.compose)

    // 站点识别逻辑的 JVM 测试：org.json 要给"真"实现，否则 android.jar 里的桩会抛异常
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
