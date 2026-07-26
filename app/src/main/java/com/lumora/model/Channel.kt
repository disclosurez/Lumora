package com.lumora.model

enum class MediaType {
    LIVE, MOVIE, SERIES
}

data class Channel(
    val id: String = "",
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgChno: String? = null,
    val mediaType: MediaType = MediaType.LIVE,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null
)
