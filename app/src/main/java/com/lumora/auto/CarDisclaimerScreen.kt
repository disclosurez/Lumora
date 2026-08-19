package com.lumora.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
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
 */
internal fun disclaimerTemplate(carContext: CarContext, onAccept: () -> Unit): Template =
    MessageTemplate.Builder(carContext.getString(com.lumora.R.string.car_disclaimer))
        .setTitle(carContext.getString(com.lumora.R.string.app_name))
        .setHeaderAction(Action.APP_ICON)
        .addAction(
            Action.Builder()
                .setTitle(carContext.getString(com.lumora.R.string.ui_not_driving_continue))
                .setOnClickListener { onAccept() }
                .build()
        )
        .build()
