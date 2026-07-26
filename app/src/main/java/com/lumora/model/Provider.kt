package com.lumora.model

enum class ProviderType {
    M3U, XTREAM
}

data class Provider(
    val name: String = "",
    val type: ProviderType = ProviderType.M3U,
    val serverUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val m3uUrl: String? = null,
    val userAgent: String? = null
)
