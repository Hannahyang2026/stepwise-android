// 用 Kotlin 编译 App，用 Compose 构建界面，用 KSP 生成 Room 数据访问代码。
plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("com.google.devtools.ksp") version "2.2.21-2.0.4"
}

android {
    // minSdk 26 支持 Android 8 起的设备，签名通过外部 apksigner 完成。
    namespace = "com.hannah.stepwise"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hannah.stepwise"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "2.1.18"
    }
    buildFeatures { compose = true }
    compileOptions {
        // 为旧版 Android 补齐 Markdown 解析器用到的新版 Java 集合方法。
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // BOM 统一 Compose 相关库的版本，Room 保存手机上的课程数据。
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // CommonMark 解析模型返回的 Markdown，界面直接用 Compose 显示。
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Debug 与 Release 共用迁移历史。联合构建时先导出同一份 Debug 架构，避免两个任务同时写 JSON。
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}
