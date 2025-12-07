plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mememanagement"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.mememanagement"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("io.coil-kt:coil:2.6.0")       // 核心库 (如果已有可忽略)
    implementation("io.coil-kt:coil-gif:2.6.0")   // 支持 GIF
    implementation("io.coil-kt:coil-video:2.6.0") // 支持 WebM (作为视频帧预览)
    // 图标扩展库 (为了使用 Folder, ArrowUpward 等图标)
    implementation("androidx.compose.material:material-icons-extended")

    // === 这里是我们添加的工具 ===
    // 1. 联网下载工具 (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 2. 图片显示工具 (Coil)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 3. 数据备份工具 (Gson)
    implementation("com.google.code.gson:gson:2.10.1")

    // 4. 解压 ZIP 工具 (Commons Compress)
    implementation("org.apache.commons:commons-compress:1.26.1")

}