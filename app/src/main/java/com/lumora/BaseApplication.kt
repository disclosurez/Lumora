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

    lateinit var database: LumoraDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Google Cast framework (required before any Cast calls)
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
        } catch (_: Exception) {
            // Google Play Services not available on this device
        }

        // Loads the native QuickJS .so; every QuickJSContext.create() call throws until this
        // has run once (see com.lumora.plugin.js.JsPluginEngine).
        com.whl.quickjs.android.QuickJSLoader.init()

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
