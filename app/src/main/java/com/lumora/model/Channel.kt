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
    val rating: String? = null,
    // ISO "YYYY-MM-DD" - only Xtream's series list actually carries a real release date
    // in bulk (movies only expose it per-item via get_vod_info, one call per movie,
    // impractical to fetch for the whole catalog just to sort it). Null means fall back
    // to year for ordering.
    val releaseDate: String? = null
)
