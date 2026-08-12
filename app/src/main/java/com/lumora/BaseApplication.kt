package com.lumora

import android.app.Application
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.remote.jellyfin.JellyfinAuthInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class BaseApplication : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set

    /** See [onCreate] - epoch millis at process start. */
    var processStartedAt: Long = 0L
        private set

    lateinit var database: LumoraDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Wall-clock zero for the cold-start budget: the first line of app code that runs in
        // a fresh process. Everything MainActivity reports as "time to first content" is
        // measured from here, so the number matches what someone counting out loud sees.
        processStartedAt = System.currentTimeMillis()

        // Initialize Google Cast framework (required before any Cast calls)
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
        } catch (_: Exception) {
            // Google Play Services not available on this device
        }

        // Loads the native QuickJS .so; every QuickJSContext.create() call throws until this
        // has run once (see com.lumora.plugin.js.JsPluginEngine).
        com.whl.quickjs.android.QuickJSLoader.init()

        // Application context for the ported site-scraper stack (com.lumora.scraper) - its
        // network layer reads a bundled root certificate out of raw resources and shares the
        // WebView cookie store, both of which need a Context that no scraper call carries.
        com.lumora.scraper.ScraperApp.init(this)


        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
            .dns(okhttp3.internal.platform.Platform.get().let { okhttp3.Dns.SYSTEM })
            .addInterceptor(JellyfinAuthInterceptor())
            .cache(Cache(File(cacheDir, "okhttp_cache"), 50L * 1024 * 1024))  // 50MB disk cache
            .build()

        database = LumoraDatabase.getInstance(this)
    }

    companion object {
        lateinit var instance: BaseApplication
            private set
    }
}
