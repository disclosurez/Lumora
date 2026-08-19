package com.lumora.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.lumora.model.Channel

/**
 * The car's channel list: Favourites and Recent first, then the provider's categories.
 *
 * Everything here is a template rather than a View - the host draws it, so the app has no say
 * in the styling and, more to the point, is held to the host's limits. Those limits are the
 * reason for the paging below: a car list is capped (six rows on most head units, and the
 * host truncates silently past its own maximum), so a 4000-channel category is chunked into
 * pages the driver can step through rather than a list that just stops.
 */
class CarBrowseScreen(
    carContext: CarContext,
    private val session: LumoraCarSession,
    private val title: String = "Lumora",
    /** Null on the root screen, which lists sections rather than channels. */
    private val channels: List<Channel>? = null,
    private val page: Int = 0,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        // Before anything else, and on every screen rather than only the root one - see
        // disclaimerTemplate. A host that opens the app somewhere other than the root screen
        // would otherwise show the channel list with the warning never seen.
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

        val list = if (channels == null) rootList() else channelList(channels)
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(if (channels == null) Action.APP_ICON else Action.BACK)
            .setSingleList(list)
            .build()
    }

    /** Sections: the two shortcuts that matter while driving, then the categories. */
    private fun rootList(): ItemList {
        val builder = ItemList.Builder()
        val playback = session.playback

        val favourites = playback.favourites()
        if (favourites.isNotEmpty()) {
            builder.addItem(sectionRow(carContext.getString(com.lumora.R.string.ui_favourites), carContext.getString(com.lumora.R.string.ui_channels_count, favourites.size), favourites))
        }
        val recents = playback.recents()
        if (recents.isNotEmpty()) {
            builder.addItem(sectionRow(carContext.getString(com.lumora.R.string.ui_recent), carContext.getString(com.lumora.R.string.ui_channels_count, recents.size), recents))
        }
        builder.addItem(sectionRow(carContext.getString(com.lumora.R.string.ui_all_channels), carContext.getString(com.lumora.R.string.ui_channels_count, playback.channels.size), playback.channels))
        for ((category, items) in playback.categories()) {
            builder.addItem(sectionRow(category, carContext.getString(com.lumora.R.string.ui_channels_count, items.size), items))
        }
        return builder.build()
    }

    private fun sectionRow(title: String, subtitle: String, items: List<Channel>): Row =
        Row.Builder()
            .setTitle(title)
            .addText(subtitle)
            .setBrowsable(true)
            .setOnClickListener {
                screenManager.push(CarBrowseScreen(carContext, session, title, items))
            }
            .build()

    /**
     * One page of channels, plus a "More" row when there are others behind it. Pushing a new
     * screen per page (rather than growing one list) keeps BACK meaning "up one page", which
     * is the only navigation a driver can use without reading.
     */
    private fun channelList(items: List<Channel>): ItemList {
        val builder = ItemList.Builder()
        val start = page * PAGE_SIZE
        val pageItems = items.drop(start).take(PAGE_SIZE)
        for (channel in pageItems) {
            builder.addItem(
                Row.Builder()
                    .setTitle(CarText.create(channel.name))
                    .apply { channel.categoryName?.takeIf { it.isNotBlank() }?.let { addText(it) } }
                    .setOnClickListener {
                        session.playback.play(channel)
                        screenManager.push(CarPlayerScreen(carContext, session, items))
                    }
                    .build()
            )
        }
        if (start + PAGE_SIZE < items.size) {
            builder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(com.lumora.R.string.ui_more))
                    .addText(carContext.getString(com.lumora.R.string.ui_more_count, items.size - start - PAGE_SIZE))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarBrowseScreen(carContext, session, title, items, page + 1))
                    }
                    .build()
            )
        }
        return builder.build()
    }

    private companion object {
        /** Under every head unit's row cap, with a row to spare for "More…". */
        const val PAGE_SIZE = 5
    }
}
