package com.lumora.scraper

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * Ambient handles the ported scraper stack needs and Lumora's single-Activity design does not
 * otherwise hand it: an application [Context] (raw resources for the bundled ISRG root, the
 * WebView cookie store, Cronet-style caches) and the Activity currently on screen.
 *
 * The upstream code these files came from was an `Application` subclass holding both. Lumora
 * already has its own Application and its own Activity, so this is a plain holder that
 * [com.lumora.LumoraApp] and [com.lumora.MainActivity] push into instead - the ~30 call sites
 * inside `com.lumora.scraper` keep referring to it exactly as they did upstream.
 *
 * [currentActivity] matters specifically for the Cloudflare bypass: [utils.WebViewResolver] runs
 * headless first and only needs an Activity when a challenge has to be promoted to a visible
 * dialog, which can happen several seconds into a background fetch that no Activity started.
 * It is held weakly - a scraper fetch outliving the Activity that triggered it must not pin it.
 */
object ScraperApp {

    @Volatile
    private var appContext: Context? = null

    private var activityRef: WeakReference<Activity>? = null

    /** Application context. Throws only if a scraper call somehow runs before [init]. */
    val instance: Context
        get() = appContext
            ?: error("ScraperApp.init() has not run - call it from LumoraApp.onCreate()")

    /** True once [init] has run, for callers that would rather no-op than throw. */
    val isReady: Boolean
        get() = appContext != null

    /** The Activity on screen, or null if none is (the bypass falls back to headless-only). */
    val currentActivity: Activity?
        get() = activityRef?.get()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setCurrentActivity(activity: Activity?) {
        activityRef = activity?.let { WeakReference(it) }
    }
}
