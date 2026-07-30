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

# jlibtorrent - JNI resolves these reflectively by exact name/signature; stripping/renaming
# breaks native calls with no compile-time warning (com.lumora.torrent.TorrentEngine/PieceGate).
-keep class com.frostwire.jlibtorrent.** { *; }
-dontwarn com.frostwire.jlibtorrent.**

# NanoHTTPD (com.lumora.torrent.StreamHttpServer)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# quickjs-wrapper - JNI callbacks (JSCallFunction implementations) resolved by class name.
-keep class com.whl.quickjs.wrapper.** { *; }
-dontwarn com.whl.quickjs.wrapper.**
