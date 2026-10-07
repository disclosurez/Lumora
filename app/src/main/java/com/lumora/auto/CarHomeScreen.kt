package com.lumora.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import com.lumora.model.Channel
import com.lumora.model.MediaType

/**
 * The car home: Live TV, Movies and Series over the cached catalogue.
 *
 * Three tabs rather than one flat list, because that is the shape the phone app has and the
 * shape a driver already knows. Each tab lists the shortcuts that matter (favourites and
 * recents on Live) followed by the provider's categories; a category opens a paged list
 * screen (see [CarBrowseScreen]) and a row in that list starts playback ([CarPlayerScreen]).
 *
 * [TabTemplate] is a Car App Library feature that older hosts do not implement, so hosts
 * below [TAB_API_LEVEL] get the Live tab as a plain list instead - the same rows, just
 * without the tab bar.
 */
class CarHomeScreen(
    carContext: CarContext,
    private val session: LumoraCarSession,
) : Screen(carContext), TabTemplate.TabCallback {

    private var activeTab: String = TAB_LIVE

    override fun onTabSelected(contentId: String) {
        activeTab = contentId
        invalidate()
    }

    override fun onGetTemplate(): Template {
        if (!session.disclaimerAccepted) {
            return disclaimerTemplate(carContext) {
                session.disclaimerAccepted = true
                invalidate()
            }
        }

        if (session.playback.channels.isEmpty()) session.playback.loadCatalog()

        if (session.playback.channels.isEmpty()) {
            return MessageTemplate.Builder(
                carContext.getString(com.lumora.R.string.ui_car_no_channels)
            )
                .setTitle(carContext.getString(com.lumora.R.string.app_name))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        return if (carContext.carAppApiLevel >= TAB_API_LEVEL) tabbedTemplate() else liveOnlyTemplate()
    }

    private fun tabbedTemplate(): Template =
        TabTemplate.Builder(this)
            .setHeaderAction(Action.APP_ICON)
            .addTab(tab(TAB_LIVE, com.lumora.R.string.tab_live_tv))
            .addTab(tab(TAB_MOVIES, com.lumora.R.string.films_tab))
            .addTab(tab(TAB_SERIES, com.lumora.R.string.series_tab))
            .setActiveTabContentId(activeTab)
            .setTabContents(TabContents.Builder(sectionList(activeTab)).build())
            .build()

    private fun tab(contentId: String, titleRes: Int): Tab =
        Tab.Builder()
            .setTitle(carContext.getString(titleRes))
            .setIcon(CarIcon.APP_ICON)
            .setContentId(contentId)
            .build()

    /** Pre-tabs hosts: the Live tab's list on its own. */
    private fun liveOnlyTemplate(): Template = sectionList(TAB_LIVE)

    /**
     * One tab's content: the shortcuts (Live only), the "all" row, then the categories of
     * whatever that tab holds. A tab with nothing cached says so rather than offering an
     * empty list.
     */
    private fun sectionList(tab: String): ListTemplate {
        val playback = session.playback
        val all = when (tab) {
            TAB_MOVIES -> playback.movies
            TAB_SERIES -> playback.series
            else -> playback.live
        }
        val titleRes = when (tab) {
            TAB_MOVIES -> com.lumora.R.string.films_tab
            TAB_SERIES -> com.lumora.R.string.series_tab
            else -> com.lumora.R.string.tab_live_tv
        }

        val builder = ItemList.Builder()
        if (all.isEmpty()) {
            builder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(com.lumora.R.string.ui_car_tab_empty))
                    .build()
            )
        } else {
            if (tab == TAB_LIVE) {
                val favourites = playback.favourites().filter { it.mediaType == MediaType.LIVE }
                if (favourites.isNotEmpty()) {
                    builder.addItem(sectionRow(carContext.getString(com.lumora.R.string.ui_favourites), favourites))
                }
                val recents = playback.recents().filter { it.mediaType == MediaType.LIVE }
                if (recents.isNotEmpty()) {
                    builder.addItem(sectionRow(carContext.getString(com.lumora.R.string.ui_recent), recents))
                }
                builder.addItem(sectionRow(carContext.getString(com.lumora.R.string.ui_all_channels), all))
            } else {
                builder.addItem(sectionRow(carContext.getString(titleRes), all))
            }
            for ((category, items) in playback.categories(all)) {
                builder.addItem(sectionRow(category, items))
            }
        }

        return ListTemplate.Builder()
            .setTitle(carContext.getString(titleRes))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(builder.build())
            .build()
    }

    private fun sectionRow(title: String, items: List<Channel>): Row =
        Row.Builder()
            .setTitle(title)
            .addText(carContext.getString(com.lumora.R.string.ui_car_item_count, items.size))
            .setBrowsable(true)
            .setOnClickListener {
                screenManager.push(CarBrowseScreen(carContext, session, title, items))
            }
            .build()

    private companion object {
        const val TAB_LIVE = "live"
        const val TAB_MOVIES = "movies"
        const val TAB_SERIES = "series"

        /** TabTemplate arrived with Car App API level 2; older hosts get the flat list. */
        const val TAB_API_LEVEL = 2
    }
}
