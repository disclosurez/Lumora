package com.lumora.auto

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.lumora.model.Channel

/**
 * Video on the car screen.
 *
 * The route is indirect and every step of it is load-bearing:
 *
 *  1. The host hands the app a drawing [android.view.Surface] through [SurfaceCallback].
 *  2. That surface backs a [VirtualDisplay] - a real, if invisible, second screen.
 *  3. A [CarPresentation] is shown on that display, which makes it an ordinary window with
 *     an ordinary View hierarchy.
 *  4. ExoPlayer renders into the SurfaceView inside it, exactly as it does on the phone.
 *
 * Drawing straight onto the host's surface would skip steps 2-4, but then the car screen can
 * only ever be one video frame - no overlay, no status text, no layout. The Presentation is
 * what makes it a screen rather than a pixel buffer.
 *
 * The template itself ([PaneTemplate]) is nearly empty on purpose: the host draws it *over*
 * the surface, so it is chrome, not content. Controls live in the template rather than on the
 * surface because the surface receives no touch of its own - split between the action strip
 * and the pane only because of the library's limit on titled strip actions (see
 * [onGetTemplate]).
 */
class CarPlayerScreen(
    carContext: CarContext,
    private val session: LumoraCarSession,
    /** The list the channel was picked from - what previous/next step through. */
    private val queue: List<Channel>,
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null

    init {
        lifecycle.addObserver(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        tearDown()
    }

    override fun onGetTemplate(): Template {
        val channel = session.playback.current
        // Where each control sits is dictated by the library, not by taste: a template's
        // action strip takes at most two actions and at most *one* of them may carry a custom
        // title, so the three controls as three titled strip actions threw
        // IllegalArgumentException out of setActionStrip and the player screen never rendered
        // at all. Play/pause - the one a driver reaches for without looking, and the one a
        // rotary head unit can reliably focus - keeps the strip; previous/next move into the
        // pane, which allows two titled actions of its own.
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(channel?.name ?: carContext.getString(com.lumora.R.string.app_name))
                    .addText(carContext.getString(com.lumora.R.string.ui_playing))
                    .build()
            )
            .addAction(textAction(carContext.getString(com.lumora.R.string.ui_prev)) { session.playback.step(queue, -1); refreshAfterInput() })
            .addAction(textAction(carContext.getString(com.lumora.R.string.ui_next)) { session.playback.step(queue, 1); refreshAfterInput() })
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(channel?.name ?: carContext.getString(com.lumora.R.string.app_name))
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        textAction(if (session.playback.isPlaying) carContext.getString(com.lumora.R.string.ui_pause) else carContext.getString(com.lumora.R.string.play)) {
                            session.playback.togglePlayPause(); refreshAfterInput()
                        }
                    )
                    .build()
            )
            .build()
    }

    private fun textAction(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener(onClick).build()

    /** A button press changes what the template says (Play <-> Pause, new channel name).
     *  Nothing else redraws: Android Auto terminates an app that pushes template updates
     *  faster than a user can cause them. */
    private fun refreshAfterInput() {
        presentation?.setStatus(session.playback.current?.name.orEmpty())
        invalidate()
    }

    // ── Surface -> VirtualDisplay -> Presentation ──

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        tearDown()
        val surface = surfaceContainer.surface ?: return
        val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        virtualDisplay = displayManager.createVirtualDisplay(
            "LumoraCar",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
        val display = virtualDisplay?.display ?: return
        presentation = CarPresentation(carContext, display).apply {
            show()
            // The SurfaceView only exists once the Presentation's window is created, which
            // show() guarantees - so the player is attached here rather than at construction.
            session.playback.setSurfaceView(videoSurface)
            setStatus(session.playback.current?.name.orEmpty())
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = tearDown()

    override fun onVisibleAreaChanged(visibleArea: Rect) = Unit

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    private fun tearDown() {
        session.playback.setSurface(null)
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

}
