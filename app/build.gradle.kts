plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.hellocompose"
    // namespace 留旧值：它只决定 R / BuildConfig 的包路径，换掉要连带移动整个
    // 源码目录，对最终 APK 的包名没有影响。真正对外的是下面的 applicationId。
    compileSdk = 37
    compileSdkMinor = 2

    // 站点配置模板只放在 src/debug/assets 下：debug 包能拿它当格式参考，
    // release 包里因此一个站点 JSON 都不存在 —— 避免分发时带上任何具体站点。
    // 正式包第一次启动就是空列表，站点全由用户自己填地址生成。

    defaultConfig {
        applicationId = "com.kylin.mangaview"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 公开分发的包用 debug 签名：装得上、能升级，但换台机器签出来的名字不同，
            // 所以只适合自己分发/侧载，不要指望它能上架商店或用它做长期增量升级。
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

    // 识别逻辑是纯 JVM 代码，单元测试里直接连真实站点验证，输出打到控制台
    testOptions {
        unitTests.all {
            // -DliveSite=... 默认只设到 Gradle 守护进程，fork 出来的测试 JVM 读不到，
            // 必须显式透传；否则实连测试永远走「跳过」，看起来是通过了
            it.systemProperty("liveSite", System.getProperty("liveSite") ?: "")
            // 语料基线的更新开关，同理必须透传（-Dautogen.corpus.update=true 才会重写 baseline.json）
            it.systemProperty("autogen.corpus.update", System.getProperty("autogen.corpus.update") ?: "")
            it.systemProperty("autogen.corpus.reportDir", System.getProperty("autogen.corpus.reportDir") ?: "")
            it.systemProperty("autogen.corpus.baseline", System.getProperty("autogen.corpus.baseline") ?: "")
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
