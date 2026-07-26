package com.lumora.player

import android.content.Context
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast options provider required by the Google Cast framework.
 * Configures the Cast receiver (Chromecast) app ID and supported namespaces.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId("CC1AD845")
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
