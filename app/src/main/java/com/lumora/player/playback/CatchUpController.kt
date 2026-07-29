package com.lumora.player.playback

import com.lumora.model.Channel
import com.lumora.model.Provider
import com.lumora.model.ProviderType

/**
 * Controls catch-up / timeshift playback for live TV.
 * Detects if a channel supports catch-up and builds the appropriate URL.
 */
class CatchUpController {

    /**
     * Check if a channel supports catch-up playback.
     */
    fun supportsCatchUp(channel: Channel, provider: Provider): Boolean {
        return provider.type == ProviderType.XTREAM && channel.tvgId != null
    }

    /**
     * Build a catch-up URL for a program that already aired.
     */
    fun buildCatchUpUrl(
        channel: Channel,
        provider: Provider,
        startTimestamp: Long,
        durationSeconds: Int = 7200
    ): String? {
        return CatchUpUrlBuilder.buildXtreamCatchUpUrl(
            provider = provider,
            streamId = channel.id,
            containerExtension = "ts",
            startTimestamp = startTimestamp,
            durationSeconds = durationSeconds
        )
    }
}
