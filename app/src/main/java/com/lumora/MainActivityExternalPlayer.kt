package com.lumora

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.lumora.player.ExternalPlayer

// ── External playback hand-off ──────────────────
//
// Some formats the app cannot play are formats the *device* cannot play: a stick with no
// Dolby licence has no AC3/E-AC3 MediaCodec, so those streams run with picture and silence
// and ExoPlayer reports nothing wrong - an unsupported track is unsupported, not failed.
// Shipping software decoders fixes it and costs tens of megabytes of FFmpeg per ABI; VLC and
// friends already carry that on the same device, so the stream is handed over instead.
//
// Two ways in: the OPEN IN button in the player controls, and an offer that appears by itself
// when the app detects it cannot do the job (audio it has no decoder for, or a hard playback
// error). The offer is one dialog, remembers "don't ask again" per install, and never appears
// on a device with no other video player on it.

/** Audio formats a device commonly has no decoder for - used only to word the prompt. */
private val LICENCED_AUDIO_MIMES = mapOf(
    "audio/ac3" to "Dolby Digital (AC3)",
    "audio/eac3" to "Dolby Digital Plus (E-AC3)",
    "audio/eac3-joc" to "Dolby Digital Plus (E-AC3)",
    "audio/true-hd" to "Dolby TrueHD",
    "audio/vnd.dts" to "DTS",
    "audio/vnd.dts.hd" to "DTS-HD",
)

/**
 * Send whatever is playing to another video app, at the position reached here.
 *
 * The app keeps playing behind the hand-off until the user comes back, so it is paused first -
 * two decoders on one live stream is both an audio mess and, on a provider that counts
 * connections, a second connection.
 */
internal fun MainActivity.openInExternalPlayer(preselectedPackage: String? = null) {
    val stream = playerManager.lastResolvedStream
    if (stream == null) {
        Toast.makeText(this, getString(R.string.play_nothing_playing), Toast.LENGTH_SHORT).show()
        return
    }
    val title = nowPlayingChannel?.name
    val position = playerManager.currentPosition.takeIf { it > 0 } ?: 0L

    val launch = { packageName: String? ->
        playerManager.pause()
        val intent = ExternalPlayer.buildIntent(
            context = this,
            url = stream.url,
            title = title,
            userAgent = stream.userAgent,
            headers = stream.headers,
            positionMs = position,
            packageName = packageName,
        )
        if (!ExternalPlayer.launch(this, intent)) {
            Toast.makeText(this, getString(R.string.play_no_app_can_open), Toast.LENGTH_LONG).show()
        }
    }

    if (preselectedPackage != null) {
        launch(preselectedPackage)
        return
    }
    // A remembered choice skips the picker; the picker's own last entry is how it gets unset.
    val remembered = prefs.getString(PREF_EXTERNAL_PLAYER_PACKAGE, null)
    if (remembered != null) {
        launch(remembered)
        return
    }

    val installed = ExternalPlayer.installedPlayers(this)
    if (installed.isEmpty()) {
        // Nothing recognised, but something unrecognised may still handle video - let the
        // system chooser decide rather than refusing here.
        launch(null)
        return
    }
    val labels = installed.map { it.label } + listOf(getString(R.string.play_other_app), getString(R.string.play_always_use))
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.play_play_in))
        .setItems(labels.toTypedArray()) { _, which ->
            when (which) {
                installed.size -> launch(null)
                installed.size + 1 -> chooseDefaultExternalPlayer()
                else -> launch(installed[which].packageName)
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/** Picks the app that external playback always uses, so the picker stops appearing. */
internal fun MainActivity.chooseDefaultExternalPlayer() {
    val installed = ExternalPlayer.installedPlayers(this)
    val labels = installed.map { it.label } + listOf(getString(R.string.play_ask_every_time))
    val current = prefs.getString(PREF_EXTERNAL_PLAYER_PACKAGE, null)
    val checked = installed.indexOfFirst { it.packageName == current }.takeIf { it >= 0 } ?: installed.size
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.play_default_external_player))
        .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
            dialog.dismiss()
            if (which == installed.size) {
                prefs.edit().remove(PREF_EXTERNAL_PLAYER_PACKAGE).apply()
                Toast.makeText(this, getString(R.string.play_external_player_ask_every_time), Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString(PREF_EXTERNAL_PLAYER_PACKAGE, installed[which].packageName).apply()
                Toast.makeText(this, getString(R.string.play_external_player_label, installed[which].label), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/**
 * The offer, shown when the app has concluded it cannot play this properly. [reason] is what
 * went wrong, in the user's terms - it is the whole value of the prompt, since "try another
 * player" without a reason reads as the app being flaky.
 */
internal fun MainActivity.suggestExternalPlayer(reason: String) {
    if (!isPlayerVisible) return
    if (!prefs.getBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, true)) return
    if (externalPlayerSuggestedForStream) return
    if (playerManager.lastResolvedStream == null) return
    // Nothing to suggest on a device with no other video app - the prompt would be a dead end.
    if (!ExternalPlayer.canHandleVideo(this)) return
    externalPlayerSuggestedForStream = true

    externalPlayerDialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.play_in_another_app))
        .setMessage(getString(R.string.play_suggest_body, reason))
        .setPositiveButton(getString(R.string.open_in_external_player_short)) { _, _ -> openInExternalPlayer() }
        .setNegativeButton(getString(R.string.play_stay_here), null)
        .setNeutralButton(getString(R.string.play_dont_ask_again)) { _, _ ->
            prefs.edit().putBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, false).apply()
            Toast.makeText(this, getString(R.string.play_turn_back_on_settings), Toast.LENGTH_LONG).show()
        }
        .setOnDismissListener { externalPlayerDialog = null }
        .show()
}

/**
 * Silence-detector, run when the track list settles.
 *
 * The case worth catching: the media has audio, and not one of its audio tracks is something
 * this device can decode. ExoPlayer renders the video and stays quiet about it, so without
 * this check the user sees a working picture and assumes the stream is broken. Anything that
 * *is* supported means the app is doing its job, whatever the format.
 */
internal fun MainActivity.checkForUndecodableAudio(tracks: Tracks) {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isEmpty()) return
    val anySupported = audioGroups.any { group ->
        (0 until group.length).any { group.isTrackSupported(it) }
    }
    if (anySupported) return

    val formatName = audioGroups.asSequence()
        .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) } }
        .mapNotNull { it.sampleMimeType?.lowercase() }
        .mapNotNull { LICENCED_AUDIO_MIMES[it] }
        .firstOrNull()
    suggestExternalPlayer(
        if (formatName != null) getString(R.string.play_audio_no_decoder_named, formatName)
        else getString(R.string.play_audio_no_decoder)
    )
}

/** Wires the OPEN IN button in the player controls. */
internal fun MainActivity.setupExternalPlayerButton(button: View) {
    button.setOnClickListener { openInExternalPlayer() }
    button.setOnLongClickListener { chooseDefaultExternalPlayer(); true }
}

/** Package-visibility-safe label for the settings row. */
internal fun MainActivity.externalPlayerSummary(context: Context): String =
    prefs.getString(PREF_EXTERNAL_PLAYER_PACKAGE, null)
        ?.let { ExternalPlayer.labelFor(context, it) }
        ?: context.getString(R.string.play_ask_every_time)
