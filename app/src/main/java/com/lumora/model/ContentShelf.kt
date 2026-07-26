package com.lumora.model

data class ContentShelf(
    val title: String,
    val items: List<Channel>,
    val pinned: Boolean = false
)
