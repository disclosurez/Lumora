package com.lumora.auto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The car screen's actual View hierarchy.
 *
 * A [Presentation] renders onto a secondary [Display] - and the car's drawing surface can be
 * turned into one via VirtualDisplay (see [CarPlayerScreen]). That indirection is what buys
 * real Views on the head unit: a template can only describe rows and actions, but a
 * Presentation is an ordinary window, so a SurfaceView inside it takes video exactly as it
 * does on the phone.
 *
 * Kept deliberately plain - a video surface and a title - because everything the driver
 * interacts with lives in the template's action strip, which the host draws over this.
 */
class CarPresentation(context: Context, display: Display) : Presentation(context, display) {

    /** Created eagerly (not lateinit) so reading it is safe immediately after construction -
     *  the car screen attaches the player's video surface right after show(), before this
     *  Presentation's onCreate() may have run, and a lateinit read there crashed on rapid
     *  surface handoff. A SurfaceView simply renders once its window exists, like any
     *  Activity's, so creating it early is harmless. */
    val videoSurface: SurfaceView = SurfaceView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private lateinit var titleLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        titleLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 24, 24, 24)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        }
        root.addView(videoSurface)
        root.addView(titleLabel)
        setContentView(root)
    }

    /** What is playing, and - when the car is moving - why there is no picture. */
    fun setStatus(text: String) {
        if (::titleLabel.isInitialized) titleLabel.text = text
    }
}
