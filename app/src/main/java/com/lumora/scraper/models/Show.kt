package com.lumora.scraper.models

import com.lumora.scraper.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
