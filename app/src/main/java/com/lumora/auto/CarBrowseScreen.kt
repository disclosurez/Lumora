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
 * One section of the car catalogue as a list: every row is a playable item, and tapping one
 * starts playback on [CarPlayerScreen].
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
    private val title: String,
    private val channels: List<Channel>,
    private val page: Int = 0,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        // Before anything else - a host that opens the app on a screen of its own choosing
        // would otherwise show content with the warning never seen.
        if (!session.disclaimerAccepted) {
            return disclaimerTemplate(carContext) {
                session.disclaimerAccepted = true
                invalidate()
            }
        }

        if (channels.isEmpty()) {
            return MessageTemplate.Builder(
                carContext.getString(com.lumora.R.string.ui_car_no_channels)
            )
                .setTitle(carContext.getString(com.lumora.R.string.app_name))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(channelList())
            .build()
    }

    /**
     * One page of channels, plus a "More" row when there are others behind it. Pushing a new
     * screen per page (rather than growing one list) keeps BACK meaning "up one page", which
     * is the only navigation a driver can use without reading.
     */
    private fun channelList(): ItemList {
        val builder = ItemList.Builder()
        val start = page * PAGE_SIZE
        val pageItems = channels.drop(start).take(PAGE_SIZE)
        for (channel in pageItems) {
            builder.addItem(
                Row.Builder()
                    .setTitle(CarText.create(channel.name))
                    .apply { channel.categoryName?.takeIf { it.isNotBlank() }?.let { addText(it) } }
                    .setOnClickListener {
                        session.playback.play(channel)
                        screenManager.push(CarPlayerScreen(carContext, session, channels))
                    }
                    .build()
            )
        }
        if (start + PAGE_SIZE < channels.size) {
            builder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(com.lumora.R.string.ui_more))
                    .addText(carContext.getString(com.lumora.R.string.ui_more_count, channels.size - start - PAGE_SIZE))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarBrowseScreen(carContext, session, title, channels, page + 1))
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
