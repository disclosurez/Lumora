package com.lumora.scraper.adapters

/**
 * Shim for the upstream RecyclerView adapter the ported scrapers were written against.
 *
 * Every scraper model ([com.lumora.scraper.models.Movie], `TvShow`, `Episode`, `Season`,
 * `Genre`, `People`, `Category`) implements `AppAdapter.Item` and stamps an `itemType` on itself,
 * and `Provider.search()` is declared as `List<AppAdapter.Item>`. That is a UI concern in the
 * app these files came from - one adapter switched on `itemType` to pick a layout. Lumora renders
 * scraper results through its own `Channel`-based adapters instead
 * ([com.lumora.scraper.bridge.ScraperCatalog] does the mapping), so none of that view code came
 * across.
 *
 * What did have to come across is the *shape*: keeping the interface and the enum under the
 * original names is what lets ~160 provider and extractor files compile with no edits at all.
 * `itemType` is written by the scrapers and simply never read by Lumora.
 */
class AppAdapter {

    /** Marker every scraper-facing model implements. Only [itemType] is part of the contract. */
    interface Item {
        var itemType: Type
    }

    /**
     * Layout discriminator, preserved verbatim from upstream. Lumora ignores the value; the
     * entries exist so the scrapers' `itemType = AppAdapter.Type.MOVIE_TV_ITEM`-style
     * assignments still resolve.
     */
    enum class Type {
        CATEGORY_MOBILE_ITEM,
        CATEGORY_TV_ITEM,

        CATEGORY_MOBILE_SWIPER,
        CATEGORY_TV_SWIPER,

        EPISODE_MOBILE_ITEM,
        EPISODE_TV_ITEM,
        EPISODE_CONTINUE_WATCHING_MOBILE_ITEM,
        EPISODE_CONTINUE_WATCHING_TV_ITEM,

        FOOTER,

        FAVORITE_SECTION_HEADER,

        GENRE_GRID_MOBILE_ITEM,
        GENRE_GRID_TV_ITEM,

        HEADER,

        LOADING_ITEM,

        MOVIE_MOBILE_ITEM,
        MOVIE_TV_ITEM,
        MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
        MOVIE_CONTINUE_WATCHING_TV_ITEM,
        MOVIE_GRID_MOBILE_ITEM,
        MOVIE_GRID_TV_ITEM,
        MOVIE_SWIPER_MOBILE_ITEM,

        MOVIE_MOBILE,
        MOVIE_TV,
        MOVIE_DIRECTORS_MOBILE,
        MOVIE_DIRECTORS_TV,
        MOVIE_CAST_MOBILE,
        MOVIE_CAST_TV,
        MOVIE_RECOMMENDATIONS_MOBILE,
        MOVIE_RECOMMENDATIONS_TV,

        PEOPLE_MOBILE_ITEM,
        PEOPLE_TV_ITEM,

        PROVIDER_MOBILE_ITEM,
        PROVIDER_TV_ITEM,

        SEASON_MOBILE_ITEM,
        SEASON_TV_ITEM,

        TV_SHOW_MOBILE_ITEM,
        TV_SHOW_TV_ITEM,
        TV_SHOW_GRID_MOBILE_ITEM,
        TV_SHOW_GRID_TV_ITEM,
        TV_SHOW_SWIPER_MOBILE_ITEM,

        TV_SHOW_MOBILE,
        TV_SHOW_TV,
        TV_SHOW_SEASONS_MOBILE,
        TV_SHOW_SEASONS_TV,
        TV_SHOW_DIRECTORS_MOBILE,
        TV_SHOW_DIRECTORS_TV,
        TV_SHOW_CAST_MOBILE,
        TV_SHOW_CAST_TV,
        TV_SHOW_RECOMMENDATIONS_MOBILE,
        TV_SHOW_RECOMMENDATIONS_TV,
    }
}
