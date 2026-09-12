package com.lumora.auto.legacy

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.apps.auto.sdk.CarActivity
import com.lumora.R
import com.lumora.auto.CarForegroundService
import com.lumora.auto.CarPlayback
import com.lumora.model.Channel

/**
 * The car screen for Android 14 and below: a flat list of live channels over a video
 * surface, drawn with classic views inside a legacy [CarActivity].
 *
 * Deliberately spartan compared to the phone UI - the host surface is small, input is
 * touch + back, and this face only exists because the template app is denied on these
 * hosts. Playback itself is the same engine as the template car screen (see
 * [CarPlayback]); only the chrome differs.
 */
class LegacyCarActivity : CarActivity() {

    private lateinit var playback: CarPlayback
    private val channels = mutableListOf<Channel>()
    private lateinit var surfaceView: SurfaceView
    private lateinit var listView: ListView
    private lateinit var nowPlaying: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var emptyView: TextView

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        playback = CarPlayback(this)
        CarForegroundService.start(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        spinner = ProgressBar(this).apply {
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
            layoutParams = lp
            visibility = View.VISIBLE
        }

        listView = ListView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = View.GONE
            isFastScrollEnabled = true
            setOnItemClickListener { _: AdapterView<*>, _: View, position: Int, _: Long ->
                if (position in channels.indices) {
                    playback.play(channels[position])
                    showVideo()
                }
            }
        }

        nowPlaying = TextView(this).apply {
            val lp = FrameLayout.LayoutParams(
                MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
            setPadding(32, 24, 32, 32)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 20f
            layoutParams = lp
            visibility = View.GONE
            setOnClickListener { showList() }
        }

        emptyView = TextView(this).apply {
            val lp = FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            setPadding(48, 48, 48, 48)
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = lp
            visibility = View.GONE
        }

        root.addView(surfaceView)
        root.addView(listView)
        root.addView(emptyView)
        root.addView(nowPlaying)
        root.addView(spinner)
        setContentView(root)

        loadCatalog()
    }

    override fun onStart() {
        super.onStart()
        playback.setSurfaceView(surfaceView)
    }

    override fun onStop() {
        playback.setSurface(null)
        super.onStop()
    }

    override fun onDestroy() {
        playback.release()
        CarForegroundService.stop(this)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (listView.visibility == View.VISIBLE) {
            if (playback.current == null) {
                super.onBackPressed()
            } else {
                showVideo()
            }
        } else {
            showList()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadCatalog() {
        Thread {
            val loaded = playback.loadCatalog()
            mainHandler.post {
                channels.clear()
                channels.addAll(loaded)
                if (channels.isEmpty()) {
                    emptyView.setText(R.string.ui_car_no_channels)
                    emptyView.visibility = View.VISIBLE
                } else {
                    listView.adapter = ArrayAdapter(
                        this, android.R.layout.simple_list_item_1, channels.map { it.name }
                    )
                }
                spinner.visibility = View.GONE
                if (channels.isNotEmpty()) showList()
            }
        }.start()
    }

    private fun showList() {
        listView.visibility = View.VISIBLE
        nowPlaying.visibility = View.GONE
    }

    private fun showVideo() {
        playback.current?.let { nowPlaying.text = it.name }
        nowPlaying.visibility = View.VISIBLE
        listView.visibility = View.GONE
    }
}
