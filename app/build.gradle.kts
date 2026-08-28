plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.openlist.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.openlist.mobile"
        // FongMi's bundled FFmpeg libraries are built against Android API 24.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // FongMi's pinned FFmpeg build provides native renderers for these Android ARM ABIs. Emit
    // standalone APKs so each device downloads and installs only its own native libraries.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").jniLibs.directories.add(
        rootProject.file("third_party/media3-fongmi/runtime-libs").absolutePath,
    )

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        // The FongMi AAR ships a full FFmpeg distribution. Its JNI bridge's DT_NEEDED closure is
        // avformat -> avcodec -> swresample -> avutil; these components are not loaded or linked.
        jniLibs.excludes += setOf(
            "**/libavdevice.so",
            "**/libavfilter.so",
            "**/libswscale.so",
        )
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// FongMi's Media3 data-source fork also supports smb:// and therefore publishes SMBJ as a
// runtime dependency. OpenList playback validates HTTP(S) URLs and injects OkHttpDataSource
// directly, so that protocol stack is unreachable here. SMBJ's consumer rules otherwise keep
// its entire implementation (and Bouncy Castle) in release builds.
configurations.configureEach {
    exclude(group = "com.hierynomus", module = "smbj")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.0"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.media3:media3-decoder-ffmpeg:1.11.0")
    implementation("androidx.media3:media3-ui-compose:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.0"))
    testImplementation("com.squareup.okhttp3:mockwebserver")
    testImplementation("com.google.truth:truth:1.4.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
