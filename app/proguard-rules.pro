# Keep annotations
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Media3
-keep class androidx.media3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# libtorrent4j - the native side resolves these SWIG-generated classes, their constructors
# and their `swigCPtr`/`getCPtr` members by exact name/signature, so renaming or stripping
# any of them breaks every native call with no compile-time warning: the release build
# crashed the moment a torrent was played while debug (unminified) was fine.
# (This block named com.frostwire.jlibtorrent.** until 2.5 - the engine moved to
# libtorrent4j, see TorrentEngine's kdoc for why, and the rule was never moved with it.)
-keep class org.libtorrent4j.** { *; }
-keep interface org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# NanoHTTPD (com.lumora.torrent.StreamHttpServer)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# quickjs-wrapper - JNI callbacks (JSCallFunction implementations) resolved by class name.
-keep class com.whl.quickjs.wrapper.** { *; }
-dontwarn com.whl.quickjs.wrapper.**

# ── com.lumora.scraper (ported site scrapers) ─────────────────────────────────

# Rhino ships a java.beans-based JSON converter for desktop JVMs. Android has no
# java.beans package at all, and that code path is never reached here (AADecoder only
# evaluates AAEncode-obfuscated source), but R8 still fails the build on the dangling
# references rather than warning.
-dontwarn java.beans.**

# Retrofit builds the scrapers' service interfaces by reflecting over their generic return
# types and parameter annotations - both are erased without these, and every scraper then
# fails at runtime with an unhelpful "Unable to create converter" rather than at build time.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn retrofit2.**
-dontwarn okhttp3.**

# The scrapers' models are deserialised by name - Gson reflects over field names, and the
# kotlinx.serialization ones need their generated serializers kept alongside them.
-keep class com.lumora.scraper.models.** { *; }
-keepclassmembers class com.lumora.scraper.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.lumora.scraper.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Provider.providers and Extractor.extractors are keyed by each implementation's own `name`
# string, and several providers are matched back from a persisted name (UserPreferences.
# currentProvider). Obfuscating the classes is fine; stripping one as unreachable is not,
# since nothing references them except those two registries.
-keep class com.lumora.scraper.providers.** { *; }
-keep class com.lumora.scraper.extractors.** { *; }

# Java-WebSocket (the LAN challenge-bypass bridge) reflects over its handler methods, and
# logs through slf4j - whose runtime binder is chosen reflectively from whatever binding is on
# the classpath. There is no binding here (Android has its own logging), so slf4j no-ops; the
# dangling reference to the binder it looked for still fails the R8 build without this.
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
-dontwarn org.slf4j.**
