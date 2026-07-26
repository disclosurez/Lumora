package com.lumora.data.domain.model

/**
 * Domain model for custom channel groups.
 */
data class CustomGroup(
    val id: String,
    val name: String,
    val mediaType: String = "LIVE",
    val isHidden: Boolean = false,
    val channelIds: List<String> = emptyList()
)
