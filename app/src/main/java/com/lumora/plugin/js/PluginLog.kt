package com.lumora.plugin.js

/**
 * Thin wrapper around [android.util.Log] - unlike on a real device, `android.util.Log` isn't
 * mocked under this project's plain JVM unit tests (no Robolectric) and throws
 * `RuntimeException: ... not mocked` on any call, so logging plugin activity directly would
 * break every test that exercises [JsPluginEngine]/[JsHostImpl]. Swallows that instead of
 * letting a debugging aid take down the actual plugin run.
 */
internal object PluginLog {
    fun d(tag: String, message: String) = runCatching { android.util.Log.d(tag, message) }
    fun i(tag: String, message: String) = runCatching { android.util.Log.i(tag, message) }
    fun w(tag: String, message: String) = runCatching { android.util.Log.w(tag, message) }
}
