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
