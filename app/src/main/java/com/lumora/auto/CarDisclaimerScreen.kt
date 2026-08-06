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
        MessageTemplate.Builder(carContext.getString(com.lumora.R.string.car_disclaimer))
            .setTitle("Lumora")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("I'm not driving - continue")
                    .setOnClickListener {
                        session.disclaimerAccepted = true
                        screenManager.push(CarBrowseScreen(carContext, session))
                    }
                    .build()
            )
            .build()
}
