import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing reads from keystore.properties (gitignored - never committed) so the
// actual key/passwords never end up in source control or CI logs.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

/**
 * A credential as a quoted Java string literal for buildConfigField, from keystore.properties
 * or else the environment. Escaped rather than interpolated raw: a stray quote or backslash in
 * a secret would otherwise generate BuildConfig.java that doesn't compile, and the failure
 * would point at generated code rather than at the value that caused it.
 */
fun credentialField(propertyKey: String, envKey: String): String {
    val raw = (keystoreProperties[propertyKey] as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey).orEmpty()
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.lumora"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumora"
        minSdk = 25
        targetSdk = 36
        versionCode = 36
        versionName = "4.4.1"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // Trakt OAuth app credentials. They end up in the APK either way - a device-flow
        // client secret has to travel with the app, and Trakt's own guidance accepts that
        // for native clients - but keeping them out of the repo means a public checkout
        // carries no working key, and rotating one is a rebuild rather than a commit.
        //
        // Two sources, local first: keystore.properties (gitignored, same file the signing
        // config reads) for a developer build, then the environment for CI, which has no
        // such file and gets them from repository secrets instead. Absent from both, they
        // come through blank and the Trakt pane reports the build as unconfigured rather
        // than failing at the first request - so a fork with no secrets still builds.
        //
        // Register an app at https://trakt.tv/oauth/applications, then either add to
        // keystore.properties:
        //   traktClientId=...
        //   traktClientSecret=...
        // or set TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in the environment.
        buildConfigField("String", "TRAKT_CLIENT_ID", credentialField("traktClientId", "TRAKT_CLIENT_ID"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", credentialField("traktClientSecret", "TRAKT_CLIENT_SECRET"))

        // TMDB v3 API keys, comma-separated - TmdbClient tries them in order and falls through
        // to the next on failure, so this stays a list rather than a single key. Same two
        // sources and the same graceful absence as the Trakt pair above: with none configured
        // TmdbClient.hasKey() is false, Discover shows its empty state, and the Series/Films
        // no-provider fallback and the Trakt title resolution quietly do nothing.
        //   tmdbApiKeys=key1,key2,key3
        // or TMDB_API_KEYS in the environment.
        buildConfigField("String", "TMDB_API_KEYS", credentialField("tmdbApiKeys", "TMDB_API_KEYS"))
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

    // Media3 ExoPlayer - hardware-accelerated video playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager - background sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // DNS-over-HTTPS for com.lumora.scraper - the scraper sites are the ones ISP resolvers
    // blackhole, so their OkHttp stack resolves through a user-selectable DoH endpoint and only
    // falls back to system DNS per-lookup (see scraper/utils/DnsResolver.kt).
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Retrofit - only used by com.lumora.scraper. The ~66 ported site scrapers are all written
    // as Retrofit services returning a Jsoup Document via the inlined
    // com.tanasi.retrofit_jsoup converter, so this is what lets them compile unmodified rather
    // than being rewritten against raw OkHttp.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")

    // Rhino - runs the AAEncode-obfuscated JS that one extractor's host wraps its source list in
    // (scraper/utils/AADecoder.java). QuickJS is already here for the plugin engine but its
    // wrapper is bound to the plugin sandbox's lifecycle, not callable as a bare evaluator.
    implementation("org.mozilla:rhino:1.8.0")

    // LAN WebSocket bridge for the scraper's Cloudflare bypass: a TV that cannot clear a
    // challenge in its own WebView hands the URL to a phone browser and gets the cookies back
    // (scraper/utils/BypassWebSocket*.kt).
    implementation("org.java-websocket:Java-WebSocket:1.5.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    // 1.6.3 is the last line compatible with Kotlin 1.9.22 - the 1.8.x releases the upstream
    // scraper app used require Kotlin 2.x, which would drag the whole toolchain forward for
    // nothing this needs.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // QR code generation (ZXing)
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // In-process JS plugin engine - replaces the old Messenger/APK plugin protocol. Plain Java
    // JNI wrapper (no Kotlin-version coupling, unlike Cash App's quickjs-android/Zipline line,
    // which requires Kotlin 2.4+ and would have forced an unrelated toolchain bump here).
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")

    // HTML parsing for JS plugin host API (torrent scraper plugin uses host.parseHtml) and for
    // every com.lumora.scraper site provider.
    // 1.21.x specifically: Elements.filter(NodeFilter) was removed there, and while it existed
    // it shadowed Kotlin's Iterable.filter as a member, so the many `elements.filter { ... }`
    // calls across the ported providers resolved to the NodeFilter overload and failed.
    implementation("org.jsoup:jsoup:1.21.2")

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

    // Cast
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    // Media session / browse tree - what Android Auto's media category binds to (auto/).
    implementation("androidx.media3:media3-session:1.4.1")

    // Android Auto (see auto/ - CarAppService).
    implementation("androidx.car.app:app:1.7.0")

    // Android TV
    implementation("androidx.tvprovider:tvprovider:1.1.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Passes -Dtest.quickjs.so through to the test JVM. wrapper-android's prebuilt native lib only
// covers Android ABIs, so JsPluginEngineTest's real QuickJS execution needs a desktop build of
// wrapper-java's native lib to run locally (see JsPluginEngineTest for the build steps) - a
// no-op when the property isn't set.
tasks.withType<Test>().configureEach {
    systemProperty("test.quickjs.so", System.getProperty("test.quickjs.so") ?: "")
}
