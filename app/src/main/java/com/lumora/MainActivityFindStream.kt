package com.lumora

import com.lumora.model.Channel

/**
 * Searches every enabled source for [item] and starts playing the best one that resolves.
 *
 * [season]/[episode] pick the episode of a series; without them sources are asked for S01E01,
 * the only sane default when the caller does not know.
 *
 * Delegates to [StreamResolver], which owns the whole find-stream state machine.
 */
internal fun MainActivity.showFindStreamDialog(
    item: Channel,
    season: Int? = null,
    episode: Int? = null,
    /**
     * When set, the resolved stream is handed here instead of being played - the Download button
     * uses it to find a source for a title that has none yet. The [Channel] carries the resolved
     * url and headers; nothing is started, so no watchdog is armed.
     */
    onResolved: ((Channel) -> Unit)? = null,
) {
    StreamResolver(this, item, season, episode, onResolved).start()
}
