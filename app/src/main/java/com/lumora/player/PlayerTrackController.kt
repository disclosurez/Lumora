package com.lumora.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

data class PlayerTrack(val id: String, val name: String, val isSelected: Boolean)

/**
 * Thin wrapper around ExoPlayer's TrackSelectionParameters API for picking
 * audio/subtitle tracks. Pure Media3 logic, no UI framework dependency.
 */
class PlayerTrackController {

    fun audioTracks(player: ExoPlayer): List<PlayerTrack> = collect(player.currentTracks, C.TRACK_TYPE_AUDIO)

    fun subtitleTracks(player: ExoPlayer): List<PlayerTrack> = collect(player.currentTracks, C.TRACK_TYPE_TEXT)

    /** Select a specific audio track, or pass null to clear the override and let the system auto-select. */
    fun selectAudioTrack(player: ExoPlayer, trackId: String?) {
        if (trackId == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            return
        }
        selectTrack(player, C.TRACK_TYPE_AUDIO, trackId)
    }

    /** Pass null to turn subtitles off. */
    fun selectSubtitleTrack(player: ExoPlayer, trackId: String?) {
        if (trackId == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        selectTrack(player, C.TRACK_TYPE_TEXT, trackId)
    }

    private fun selectTrack(player: ExoPlayer, trackType: Int, trackId: String) {
        val override = findOverride(player.currentTracks, trackType, trackId) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    private fun findOverride(tracks: Tracks, trackType: Int, trackId: String): TrackSelectionOverride? {
        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (trackKey(group.mediaTrackGroup.id, i) == trackId) {
                    return TrackSelectionOverride(group.mediaTrackGroup, listOf(i))
                }
            }
        }
        return null
    }

    private fun collect(tracks: Tracks, trackType: Int): List<PlayerTrack> {
        val result = mutableListOf<PlayerTrack>()
        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                result.add(
                    PlayerTrack(
                        id = trackKey(group.mediaTrackGroup.id, i),
                        name = buildTrackName(group.getTrackFormat(i), i),
                        isSelected = group.isTrackSelected(i)
                    )
                )
            }
        }
        return result
    }

    private fun trackKey(groupId: String, index: Int) = "$groupId:$index"

    private fun buildTrackName(format: Format, index: Int): String {
        val label = format.label
        if (!label.isNullOrBlank()) return label
        val lang = format.language
        if (!lang.isNullOrBlank() && lang != "und") {
            val displayName = runCatching { Locale.forLanguageTag(lang).displayLanguage }.getOrDefault("")
            if (displayName.isNotBlank()) return displayName
            return lang
        }
        return "Track ${index + 1}"
    }
}
