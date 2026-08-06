package com.lumora.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Lumora on Android Auto.
 *
 * Android Auto has no video app category - media apps are audio-only by definition, and the
 * projected surface is not available to arbitrary Activities. Navigation template apps *are*
 * given a real drawing [android.view.Surface] (it exists so a maps app can render its own
 * map), and nothing about that surface is map-specific: ExoPlayer renders video into it the
 * same way it renders into a SurfaceView. That is the whole trick, and it is the same one
 * every other car video app on Android Auto uses.
 *
 * Consequences to be aware of, because they are not incidental:
 *  - This can never ship on Google Play. Declaring the navigation category for something that
 *    is not navigation is against the Play policy for cars, so Lumora stays a sideload.
 *  - The user has to enable "Unknown sources" in Android Auto's developer settings before the
 *    app appears in the car launcher at all.
 *  - Video while the car is moving is illegal in most places and dangerous everywhere. The
 *    picture is therefore parked-only by default (see [CarPlayerScreen]); audio continues
 *    when the car starts moving, and only the video track is dropped.
 */
class LumoraCarAppService : CarAppService() {

    /**
     * Every host is accepted, release builds included.
     *
     * The library's sample allowlist is the documented alternative, but it is a fixed list of
     * signatures - and a sideloaded app is already outside the trust model that list exists to
     * enforce. Worse, it fails at *bind* time and silently: the app is listed in the car, the
     * user taps it, nothing happens. Working sideloaded car apps allow all hosts, and so does
     * this one.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = LumoraCarSession()
}

/** One connection to the car. Owns the playback that outlives individual screens. */
class LumoraCarSession : Session(), DefaultLifecycleObserver {

    /** Created here, not per-screen: switching between the channel list and the player must
     *  not interrupt what is playing. */
    val playback: CarPlayback by lazy { CarPlayback(carContext) }

    init {
        // Session exposes its lifecycle rather than overridable callbacks, so teardown hangs
        // off an observer - without it the player (and its stream) would outlive the drive.
        lifecycle.addObserver(this)
    }

    /** Set once the driver has acknowledged the warning, for this connection only - a new
     *  session (every time the car connects) shows it again. */
    var disclaimerAccepted = false

    override fun onCreateScreen(intent: Intent): Screen = CarDisclaimerScreen(carContext, this)

    override fun onDestroy(owner: LifecycleOwner) {
        playback.release()
    }
}
