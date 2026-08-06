import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

// Release signing reads from keystore.properties (gitignored - never committed) so the
// actual key/passwords never end up in source control or CI logs.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.lumora"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumora"
        minSdk = 25
        targetSdk = 36
        versionCode = 20
        versionName = "3.3"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Distinct applicationId + label so a debug build installs alongside the release
            // "Lumora" on a device instead of replacing it. Manifest provider authorities are
            // already ${applicationId}-derived, so they follow the suffix automatically; the
            // label override lives in src/debug/res/values/strings.xml.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Extract .so files to disk instead of loading them directly from the APK. libtorrent4j's
            // native libs are not PAGE-aligned, so direct-from-apk loading throws "not PAGE-aligned -
            // cannot open directly from apk" on older devices (old Fire TV sticks); legacy extraction
            // avoids the crash at the cost of a little install-time unpacking.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Multiple jlibtorrent per-ABI artifacts carry the same license/notice files.
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Media3 ExoPlayer - hardware-accelerated video playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager - background sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // QR code generation (ZXing)
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // In-process JS plugin engine - replaces the old Messenger/APK plugin protocol. Plain Java
    // JNI wrapper (no Kotlin-version coupling, unlike Cash App's quickjs-android/Zipline line,
    // which requires Kotlin 2.4+ and would have forced an unrelated toolchain bump here).
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")

    // HTML parsing for JS plugin host API (torrent scraper plugin uses host.parseHtml).
    implementation("org.jsoup:jsoup:1.17.2")

    // Native torrent streaming engine (com.lumora.torrent) - moved in-process from the old
    // torrentplugin APK; only the scraper/search half became a JS script (torrent-search.js),
    // this half needs libtorrent itself so it stays native Kotlin.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // libtorrent4j (libtorrent 2.0.x), not FrostWire's jlibtorrent (libtorrent 1.2). 1.2 decides
    // whether it may connect to an address from the routing table it reads over netlink, and
    // Android only shows an app LAN + loopback routes - no default route - so every listen socket
    // was treated as local-network-only and the engine refused to dial a single peer or announce
    // to a single tracker (verified on device: 1000 known candidates, 0 connections).
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35")

    // Security - credential encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Cast
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    // Media session / browse tree - what Android Auto's media category binds to (auto/).
    implementation("androidx.media3:media3-session:1.4.1")

    // Android Auto (see auto/ - CarAppService).
    implementation("androidx.car.app:app:1.7.0")

    // Android TV
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("androidx.leanback:leanback:1.2.0-alpha02")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Passes -Dtest.quickjs.so through to the test JVM. wrapper-android's prebuilt native lib only
// covers Android ABIs, so JsPluginEngineTest's real QuickJS execution needs a desktop build of
// wrapper-java's native lib to run locally (see JsPluginEngineTest for the build steps) - a
// no-op when the property isn't set.
tasks.withType<Test>().configureEach {
    systemProperty("test.quickjs.so", System.getProperty("test.quickjs.so") ?: "")
}
