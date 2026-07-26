package com.lumora.data.remote.stalker

/**
 * Stalker portal compatibility fingerprint presets.
 * Different Stalker/Ministry versions require different auth approaches.
 * These presets tune the auth flow to maximize compatibility.
 */
enum class StalkerFingerprint(
    val label: String,
    val useMac: Boolean,
    val useCredentials: Boolean,
    val requireDeviceId: Boolean = true,
    val useCookieAuth: Boolean = false,
    val useTokenRefresh: Boolean = false
) {
    BASIC_MAC("Basic MAC", useMac = true, useCredentials = false, requireDeviceId = true),
    STRICT_MAG("Strict MAG", useMac = true, useCredentials = false, requireDeviceId = true, useCookieAuth = false),
    AUTH_ONLY("Auth Only", useMac = true, useCredentials = true),
    AUTH_STRICT_MAG("Auth + Strict", useMac = true, useCredentials = true, requireDeviceId = true, useCookieAuth = true),
    MODULE_GATED("Module Gated", useMac = true, useCredentials = true, requireDeviceId = true, useCookieAuth = true, useTokenRefresh = true),
    TEMP_LINK_STRICT("Temp Link Strict", useMac = true, useCredentials = false, requireDeviceId = true, useCookieAuth = false, useTokenRefresh = true)
}

/**
 * MAG device preset configurations for different STB models.
 */
enum class MagPreset(val label: String, val model: String, val vendor: String) {
    GENERIC_SAFE("Generic Safe", "MAG250", "5.0.0-release"),
    MAG250_LEGACY("MAG250 Legacy", "MAG250", "5.0.0-release"),
    MAG254_STRICT("MAG254 Strict", "MAG254", "2.28.2-release"),
    MINISTRA_MODERN("Ministra Modern", "MAG420w1", "5.0.0-release")
}
