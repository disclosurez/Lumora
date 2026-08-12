package com.lumora.scraper.utils

/**
 * Upstream loaded these three values out of an NDK library that XOR-obfuscated them at compile
 * time, generated from a `native-lib.cpp.template` filled in at build time from local
 * properties. The checked-in `native-lib.cpp` has all three placeholders resolved to empty
 * strings - there was never a value in the source to carry across - so bringing the CMake
 * toolchain into Lumora's build would add an NDK dependency to obfuscate nothing.
 *
 * Empty strings are exactly the state the upstream build produces, and the one provider that
 * reads them (CB01Provider) already treats blank as "this host is not configured" and falls
 * through to its other sources.
 */
object Keys {

    fun getUprotMsfiApiBase(): String = ""

    fun getUprotMseApiBase(): String = ""

    fun getUprotApiKey(): String = ""
}
