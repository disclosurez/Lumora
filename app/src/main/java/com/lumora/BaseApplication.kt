package com.lumora

import android.app.Application
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.sync.EpgSyncWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BaseApplication : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var database: LumoraDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
            .dns(okhttp3.internal.platform.Platform.get().let { okhttp3.Dns.SYSTEM })
            .build()

        database = LumoraDatabase.getInstance(this)

        // Schedule periodic EPG sync
        EpgSyncWorker.schedulePeriodic(this)
    }

    companion object {
        lateinit var instance: BaseApplication
            private set
    }
}
