package com.lumora.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * The first thing the car screen shows, every session.
 *
 * Not a formality and not dismissible-forever: video in a car is for a stationary vehicle or a
 * passenger display, and the one moment the person holding the phone is definitely looking at
 * the head unit is when they have just opened the app. It states what the app will do (drop to
 * audio the moment it detects motion) so the behaviour later isn't read as a fault.
 */
class CarDisclaimerScreen(
    carContext: CarContext,
    private val session: LumoraCarSession,
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        disclaimerTemplate(carContext) {
            session.disclaimerAccepted = true
            screenManager.push(CarBrowseScreen(carContext, session))
        }
}

/**
 * The disclaimer as a template rather than a screen, because the root screen is not a reliable
 * place to put it: a host is free to open the app on a screen of its own choosing, and one that
 * does lands the driver straight in the channel list having seen nothing. Every screen that can
 * show content therefore checks [LumoraCarSession.disclaimerAccepted] and renders this instead
 * until it is acknowledged, so there is no route into the app that skips it.
 *
 * A list rather than a MessageTemplate with an action button, because a head unit with no
 * touchscreen drives the screen with a knob: list rows are the one thing every such host is
 * guaranteed to step through and select, while a message template's body button is reachable
 * only where the host chooses to give it focus - and on rotary-only units (Audi MMI) it does
 * not, leaving the warning undismissable and the app unreachable behind it. The same acceptance
 * is repeated in the action strip so there are two ways to it whatever the host focuses first.
 */
internal fun disclaimerTemplate(carContext: CarContext, onAccept: () -> Unit): Template {
    // The warning is written as paragraphs in one string so it stays one translatable unit;
    // a row takes a title plus at most two lines of text, which is exactly how it splits.
    val paragraphs = carContext.getString(com.lumora.R.string.car_disclaimer)
        .split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val warning = Row.Builder()
        .setTitle(paragraphs.firstOrNull() ?: carContext.getString(com.lumora.R.string.car_disclaimer))
        .apply { paragraphs.drop(1).take(2).forEach { addText(it) } }
        .build()
    val accept = Row.Builder()
        .setTitle(carContext.getString(com.lumora.R.string.ui_not_driving_continue))
        .setOnClickListener { onAccept() }
        .build()
    return ListTemplate.Builder()
        .setTitle(carContext.getString(com.lumora.R.string.app_name))
        .setHeaderAction(Action.APP_ICON)
        .setSingleList(ItemList.Builder().addItem(warning).addItem(accept).build())
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(com.lumora.R.string.ui_not_driving_continue))
                        .setOnClickListener { onAccept() }
                        .build()
                )
                .build()
        )
        .build()
}
