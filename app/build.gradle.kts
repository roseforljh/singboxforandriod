import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kunk.singbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kunk.singbox"
        minSdk = 24
        targetSdk = 36
        
        val gitCommitCount = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 1
        val gitVersionName = project.findProperty("versionName")?.toString() ?: "1.0"
        
        versionCode = gitCommitCount
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resConfigs("zh", "en") // 仅保留中文和英文资源，减少体积
        
        // 过滤 AAR 中的 native 库，仅保留 64 位架构 (arm64-v8a, x86_64)
        // libbox.aar 包含 4 个架构，每个约 10MB+，移除 32 位架构可显著减小 APK 体积
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) {
                props.load(propsFile.inputStream())
                storeFile = rootProject.file(props.getProperty("STORE_FILE"))
                storePassword = props.getProperty("KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("KEY_ALIAS")
                keyPassword = props.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 在 debug 模式下也可以开启简单的分包优化，或者减小 ABI 范围
            // 如果仅用于本地调试，建议在 local.properties 中配置仅编译当前设备的架构
        }
    }
    
    // 注意：不使用 splits.abi，因为与 ndk.abiFilters 冲突
    // 使用 ndk.abiFilters 来过滤 AAR 中的 native 库
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 优化 JNI 库打包方式，减少安装后占用空间
        // useLegacyPackaging = false 让系统直接从 APK 读取 .so 文件，无需提取到 lib 目录
        // 要求 minSdk >= 23 (Android 6.0+)，当前 minSdk=24 满足要求
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    // 避免压缩规则集文件，提高读取效率
    aaptOptions {
        noCompress("srs")
    }
}

dependencies {
    // Sing-box 核心库 (libbox) - 本地 AAR 文件
    // 请从 sing-box 官方仓库下载或编译 libbox AAR 并放置在 app/libs 目录
    // 参见: https://github.com/SagerNet/sing-box
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    
    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Network - OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing - Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // YAML parsing
    implementation("org.yaml:snakeyaml:2.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}