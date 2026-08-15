package com.lumora

import android.animation.AnimatorInflater
import android.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import android.net.Uri
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.download.DownloadStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.Provider
import com.lumora.model.IptvProviderConfig
import com.lumora.data.IptvProviderStore
import com.lumora.pairing.QrPairingManager
import com.lumora.player.PlayerManager
import com.lumora.util.normalizeServerUrl
import com.lumora.data.local.entity.EpgSourceEntity
import com.lumora.data.backup.BackupManager
import com.lumora.data.remote.jellyfin.JellyfinProvider
import com.lumora.data.remote.plex.PlexProvider
import com.lumora.data.update.AppUpdateChecker
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.Locale

// ── Provider settings, EPG sources & backup ──
//
// Extracted from MainActivity.kt; see that file's header.
internal fun MainActivity.showAddEpgSourceDialog() {
    val input = EditText(this).apply {
        hint = getString(R.string.sett_epg_source_url_hint)
        inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val nameInput = EditText(this).apply {
        hint = getString(R.string.sett_epg_source_name_hint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 20, 40, 20)
        addView(nameInput)
        addView(input)
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.sett_add_epg_source_title))
        .setView(layout)
        .setPositiveButton(getString(R.string.add)) { _, _ ->
            val name = nameInput.text.toString().trim().ifBlank { "EPG ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}" }
            val url = input.text.toString().trim()
            if (url.isBlank()) { Toast.makeText(this, getString(R.string.sett_enter_url), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            scope.launch {
                database.epgSourceDao().insert(
                    EpgSourceEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url
                    )
                )
                // Fetch it now rather than leaving the new source dark until the next 6h
                // periodic run — adding a source and seeing no guide for hours reads as broken.
                com.lumora.data.sync.EpgSyncWorker.enqueue(this@showAddEpgSourceDialog)
                Toast.makeText(this@showAddEpgSourceDialog, getString(R.string.sett_epg_source_added), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

// ── Jellyfin Quick Connect ─────────────────────

/** Runs the Jellyfin Quick Connect handshake against [url]: starts a code (or reuses
 *  [existing] if the QR flow already started one and showed it on the phone - starting
 *  a second one here would mint a different code than what's on screen there), polls
 *  for server-side approval, then exchanges it for a session. On success, persists the
 *  session - Jellyfin is a fully independent provider slot now (can be configured and
 *  active at the same time as an IPTV one), so this no longer touches the shared
 *  `provider` field at all. Reports progress via [onStatus] so callers (the manual
 *  settings button and the QR-pairing receive handler) can show it wherever's relevant. */
internal suspend fun MainActivity.performJellyfinQuickConnect(
    url: String,
    existing: Pair<String, String>? = null,
    onStatus: (String) -> Unit
): Boolean {
    val qc = JellyfinProvider(BaseApplication.instance.okHttpClient)
    val (code, secret) = existing ?: run {
        onStatus(getString(R.string.sett_starting))
        withContext(Dispatchers.IO) { qc.startQuickConnect(url) }
            ?: run { onStatus(qc.lastQuickConnectError ?: getString(R.string.sett_qc_couldnt_start)); return false }
    }
    onStatus(getString(R.string.sett_enter_code_on_server, code))
    val deadline = System.currentTimeMillis() + 120_000L
    var approved = false
    while (System.currentTimeMillis() < deadline) {
        delay(2000)
        if (withContext(Dispatchers.IO) { qc.isQuickConnectApproved(url, secret) }) { approved = true; break }
    }
    if (!approved) { onStatus(getString(R.string.sett_qc_timed_out)); return false }
    onStatus(getString(R.string.sett_signing_in))
    val authResult = withContext(Dispatchers.IO) { qc.completeQuickConnect(url, secret) }
    val auth = authResult.getOrNull()
    if (auth == null || auth.token == null || auth.userId == null) {
        onStatus(getString(R.string.sett_qc_signin_failed))
        return false
    }
    // No password to save here - the token itself is the credential from now on.
    prefs.edit()
        .putString("jellyfin_url", url)
        .putString("jellyfin_token", auth.token)
        .putString("jellyfin_userid", auth.userId)
        .putBoolean("jellyfin_provider_enabled", true)
        .apply()
    return true
}

// ── Plex sign-in (plex.tv PIN + QR) ────────────

/**
 * Runs a full Plex sign-in and, on success, persists the slot.
 *
 * Plex has no per-server login, so this is an account flow: mint a PIN at plex.tv, show it
 * (as a QR to scan and as a code to type at plex.tv/link), poll until the user finishes
 * signing in on their phone, then ask the account which servers it can reach, let the user
 * pick when there's more than one, and probe that server's published endpoints for one that
 * actually answers from this network. What gets stored is the *server's* token - a shared
 * server issues its own, and the account token won't open it.
 *
 * [existing] is for the phone-pairing path, which has already started a PIN and shown its
 * code on the phone - starting a second one here would mint a different code than what is on
 * that screen. [onQr] receives the QR bitmap (null when the caller shows no QR, e.g. the
 * phone-pairing path where the phone already carries the link).
 */
internal suspend fun MainActivity.performPlexSignIn(
    existing: PlexProvider.PinLogin? = null,
    onQr: (android.graphics.Bitmap?) -> Unit = {},
    onCode: (String?) -> Unit = {},
    onStatus: (String) -> Unit
): Boolean {
    val plex = newPlexProvider()
    val pin = existing ?: run {
        onStatus(getString(R.string.sett_plex_starting))
        withContext(Dispatchers.IO) { plex.startPinLogin() }
            ?: run { onStatus(plex.lastAuthError ?: getString(R.string.sett_plex_couldnt_start)); return false }
    }
    onQr(runCatching { QrPairingManager.createQrBitmap(pin.authUrl) }.getOrNull())
    // Callers that have somewhere to put the code on its own show it there and take the
    // instruction line; the ones that only have a single status line (the phone-pairing
    // pane) get the code inlined into that line instead.
    onCode(pin.code)
    onStatus(getString(R.string.sett_plex_link_instructions))

    // Three minutes: the user has to pick up a phone, scan, and sign in to plex.tv, possibly
    // with two-factor. Two minutes (the Quick Connect window) proved tight for that.
    val deadline = System.currentTimeMillis() + 180_000L
    var accountToken: String? = null
    while (System.currentTimeMillis() < deadline) {
        delay(2000)
        accountToken = withContext(Dispatchers.IO) { plex.pollPin(pin.id) }
        if (accountToken != null) break
        // An expired PIN answers 404 forever, so stop rather than spin out the full window.
        if (plex.lastAuthError != null) break
    }
    if (accountToken == null) {
        onCode(null)
        onStatus(plex.lastAuthError ?: getString(R.string.sett_plex_timed_out))
        return false
    }
    // The code and the QR are spent the moment the PIN is claimed - leaving them up while
    // the server list loads invites a second, pointless trip to plex.tv.
    onQr(null)
    onCode(null)

    onStatus(getString(R.string.sett_plex_finding_servers))
    val servers = withContext(Dispatchers.IO) { plex.fetchServers(accountToken) }
    if (servers.isEmpty()) {
        onStatus(plex.lastAuthError ?: getString(R.string.sett_plex_no_servers))
        return false
    }
    val server = if (servers.size == 1) servers.first() else choosePlexServer(servers) ?: return false

    onStatus(getString(R.string.sett_plex_connecting_server, server.name))
    val base = withContext(Dispatchers.IO) { plex.pickConnection(server) }
    if (base == null) {
        onStatus(getString(R.string.sett_plex_unreachable, server.name))
        return false
    }

    prefs.edit()
        .putString("plex_url", base)
        .putString("plex_token", server.accessToken)
        // Kept so the server list can be re-read later (to switch servers) without a second
        // sign-in - the per-server token can't list an account's resources.
        .putString("plex_account_token", accountToken)
        .putString("plex_server_name", server.name)
        .putBoolean("plex_provider_enabled", true)
        .apply()
    onStatus(getString(R.string.sett_plex_signed_in, server.name))
    return true
}

/** Server picker for an account with more than one. Suspends until the user chooses; null
 *  when they dismiss it, which cancels the sign-in rather than guessing a server. */
private suspend fun MainActivity.choosePlexServer(
    servers: List<PlexProvider.PlexServerInfo>
): PlexProvider.PlexServerInfo? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    val labels = servers.map { server ->
        if (server.owned) server.name else "${server.name} (shared)"
    }.toTypedArray()
    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.sett_plex_choose_server))
        .setItems(labels) { _, which -> if (cont.isActive) cont.resume(servers[which]) }
        .setOnCancelListener { if (cont.isActive) cont.resume(null) }
        .show()
    cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
}

/** A Filters-pane checkbox with a dimmed caption line under its title - the other filter
 *  toggles carry a single line, but these need the caption to say what the toggle changes
 *  about playback. Wired straight to [key] in the shared "iptv_prefs" file, so PlayerManager
 *  sees the same value (subtitles_with_dub, subtitles_enabled) without any extra plumbing. Styled to match the
 *  static pane rows (hide-adult row's card surface, focus scale, and text hierarchy) so
 *  runtime-added rows don't read as cheaper than their XML siblings. */
/** A settings row that picks a language into [key]. Sits in General with the other
 *  whole-app choices; the Subtitles on/off switch stays under Filters with the rest of
 *  the playback toggles. */
internal fun MainActivity.languageChoiceRow(
    title: String,
    key: String,
    caption: String,
    choices: List<Pair<String, String>> = PLAYBACK_LANGUAGES,
    onSelect: ((String) -> Unit)? = null
): TextView {
    val row = TextView(this)
    row.setTextColor(getColor(R.color.text_primary))
    row.setBackgroundResource(R.drawable.card_surface_background)
    val hPad = resources.getDimensionPixelSize(R.dimen.settings_gap_l)
    val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_padding_vertical)
    row.setPadding(hPad, vPad, hPad, vPad)
    row.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale_flat)
    row.isClickable = true
    row.isFocusable = true
    row.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_m) }

    fun render() {
        val current = prefs.getString(key, "en") ?: "en"
        val display = choices.firstOrNull { it.first == current }?.second ?: current.uppercase()
        row.text = twoLineSettingsText(title, getString(R.string.sett_language_row, display, caption))
    }
    render()
    row.setOnClickListener {
        val codes = choices.map { it.first }
        val current = prefs.getString(key, "en") ?: "en"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                choices.map { it.second }.toTypedArray(),
                codes.indexOf(current).coerceAtLeast(0)
            ) { dialog, which ->
                prefs.edit().putString(key, codes[which]).apply()
                onSelect?.invoke(codes[which])
                render()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    return row
}

internal fun MainActivity.languageName(code: String): String =
    PLAYBACK_LANGUAGES.firstOrNull { it.first == code }?.second ?: code.uppercase()

/** The medium-title-over-grey-caption text every settings row uses. Shared so a plain
 *  row and a CheckBox row can't drift apart. */
internal fun MainActivity.twoLineSettingsText(title: String, subtitle: String): SpannableStringBuilder {
    val titleEnd = title.length
    val captionStart = titleEnd + 1 // skip the "\n"
    val text = SpannableStringBuilder(title).append("\n").append(subtitle)
    val bodySize = resources.getDimensionPixelSize(R.dimen.settings_text_body)
    val captionSize = resources.getDimensionPixelSize(R.dimen.settings_text_caption)
    val titleFont = ResourcesCompat.getFont(this, R.font.inter_medium) ?: Typeface.DEFAULT
    val captionFont = ResourcesCompat.getFont(this, R.font.inter_regular) ?: Typeface.DEFAULT
    text.setSpan(MainActivity.FontSpan(titleFont), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(AbsoluteSizeSpan(bodySize), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(MainActivity.FontSpan(captionFont), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(AbsoluteSizeSpan(captionSize), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text
}

internal fun MainActivity.dubCheckBoxRow(title: String, subtitle: String, key: String, onToggle: ((Boolean) -> Unit)? = null): CheckBox {
    val checkBox = CheckBox(this)
    checkBox.text = twoLineSettingsText(title, subtitle)
    checkBox.setTextColor(getColor(R.color.text_primary))
    checkBox.setBackgroundResource(R.drawable.card_surface_background)
    val hPad = resources.getDimensionPixelSize(R.dimen.settings_gap_l)
    val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_padding_vertical)
    checkBox.setPadding(hPad, vPad, hPad, vPad)
    checkBox.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale_flat)
    checkBox.isClickable = true
    checkBox.isFocusable = true
    checkBox.isChecked = prefs.getBoolean(key, false)
    checkBox.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(key, checked).apply()
        onToggle?.invoke(checked)
    }
    checkBox.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_m)
    }
    return checkBox
}

/** Whether the Settings rail is collapsed on this device: a persisted pref, except on a
 *  portrait phone, where the rail auto-hides and only the transient (unpersisted)
 *  [MainActivity.portraitSettingsRailExpanded] can bring it back - mirrors the category
 *  rail's isSidebarCollapsed() exactly. */
internal fun MainActivity.isSettingsRailCollapsed(): Boolean =
    if (isPortraitPhone()) !portraitSettingsRailExpanded
    else prefs.getBoolean(PREF_SETTINGS_RAIL_COLLAPSED, false)

/** Single place that decides the Settings rail's visibility: collapses the rail + divider
 *  and shows the floating re-expand pill in their place (the content ScrollView already
 *  fills the freed width via its layout_weight, so nothing else moves). The settings tree
 *  is inflated fresh on every open and survives rotation in place, so this is applied at
 *  inflation time in showProviderSettings() and re-applied from onConfigurationChanged.
 *  Like the category rail, the pill's footprint is reserved as extra top padding on the
 *  content scroll, so the pill floats over empty space rather than the pane's first row. */
internal fun MainActivity.applySettingsRailVisibility(root: View? = null) {
    // showProviderSettings() applies this at inflation time, which is before the tree is
    // reachable as activeSettingsOverlay (that field is only assigned just before show()) -
    // so it passes its dialogView in explicitly. Without the parameter the open-time call
    // hit the null overlay and returned, and the rail only ever collapsed on rotation.
    val view = root ?: activeSettingsOverlay?.view ?: return
    val collapsed = isSettingsRailCollapsed()
    view.findViewById<View>(R.id.settingsNavRail).visibility = if (collapsed) View.GONE else View.VISIBLE
    view.findViewById<View>(R.id.settingsNavDivider).visibility = if (collapsed) View.GONE else View.VISIBLE
    view.findViewById<View>(R.id.settingsExpandRailButton).visibility = if (collapsed) View.VISIBLE else View.GONE
    val contentScroll = view.findViewById<View>(R.id.settingsContentScroll)
    val reservePx = (56 * resources.displayMetrics.density).toInt()
    contentScroll.setPadding(
        contentScroll.paddingLeft,
        if (collapsed) reservePx else 0,
        contentScroll.paddingRight,
        contentScroll.paddingBottom
    )
}

/** Collapses the Settings rail from its Collapse row: the state persists (or flips the
 *  portrait transient), the rail hides, and focus moves to the re-expand pill since the
 *  focused row is about to disappear - mirrors collapseCategorySidebar(). */
internal fun MainActivity.collapseSettingsRail() {
    if (isPortraitPhone()) portraitSettingsRailExpanded = false
    else prefs.edit().putBoolean(PREF_SETTINGS_RAIL_COLLAPSED, true).apply()
    applySettingsRailVisibility()
    activeSettingsOverlay?.view?.findViewById<View>(R.id.settingsExpandRailButton)?.requestFocus()
    // Names the way back at the one moment the user is guaranteed to be looking at this
    // corner of the screen - an accidental collapse otherwise reads as a dead end.
    Toast.makeText(this, R.string.settings_hidden_hint, Toast.LENGTH_SHORT).show()
}

/** Applies a Typeface to a span range independent of the TextView's own typeface - lets a
 *  single two-line TextView carry a medium title over a regular caption. (TypefaceSpan's
 *  Typeface constructor is API 28+, so this hand-rolled span keeps minSdk 25 happy.) */

@Suppress("DEPRECATION")
internal fun MainActivity.showProviderSettings() {
    // Already open: unticking the last provider or plugin from inside Settings reloads, and
    // that load's "nothing configured" branch calls straight back in here - which would
    // inflate a second settings tree on top of the live one, leaving the first orphaned
    // behind it and only the second reachable by Back.
    if (activeSettingsOverlay != null) return
    // Close Search if it's open - the two share the weighted content slot and would otherwise
    // render stacked on top of each other (see showSearchDialog).
    activeSearchOverlay?.dismiss()
    // Every open of Settings on a portrait phone starts with the rail hidden, the same way
    // rotating into portrait re-hides the category rail: the panes need the full width to
    // be readable, and the tree is inflated fresh here anyway, so an expand from a previous
    // visit is not carried into this one.
    if (isPortraitPhone()) portraitSettingsRailExpanded = false
    val dialogView = layoutInflater.inflate(R.layout.activity_settings, null)
    // Deliberately no width cap here. Settings used to be pinned to 660dp and centred on
    // TV, which left a wide band of the tab background down both sides - it read as a
    // floating pop-out rather than a screen, and squeezed the two-pane layout (nav rail
    // plus content) into a column too narrow for either. It now fills its slot, and
    // reading measure is held by settings_content_inset on the content column instead.
    val typeM3u = dialogView.findViewById<View>(R.id.settingsTypeM3u)
    val typeXtream = dialogView.findViewById<View>(R.id.settingsTypeXtream)
    val typeStalker = dialogView.findViewById<View>(R.id.settingsTypeStalker)
    val typeJellyfin = dialogView.findViewById<View>(R.id.settingsTypeJellyfin)
    val typePlex = dialogView.findViewById<View>(R.id.settingsTypePlex)
    val showQrButton = dialogView.findViewById<View>(R.id.settingsShowQrButton)
    val manualDivider = dialogView.findViewById<View>(R.id.settingsManualDivider)
    val nameSection = dialogView.findViewById<View>(R.id.settingsNameSection)
    val qrSection = dialogView.findViewById<View>(R.id.settingsQrSection)
    val qrFrame = dialogView.findViewById<View>(R.id.settingsQrFrame)
    val qrImage = dialogView.findViewById<ImageView>(R.id.settingsQrImage)
    val qrStatus = dialogView.findViewById<TextView>(R.id.settingsQrStatus)
    val qrTimer = dialogView.findViewById<TextView>(R.id.settingsQrTimer)
    val m3uGroup = dialogView.findViewById<View>(R.id.settingsM3uGroup)
    val xtreamGroup = dialogView.findViewById<View>(R.id.settingsXtreamGroup)
    val stalkerGroup = dialogView.findViewById<View>(R.id.settingsStalkerGroup)
    val jellyfinGroup = dialogView.findViewById<View>(R.id.settingsJellyfinGroup)
    val plexGroup = dialogView.findViewById<View>(R.id.settingsPlexGroup)
    val m3uUrl = dialogView.findViewById<EditText>(R.id.settingsM3uUrl)
    val uaInput = dialogView.findViewById<EditText>(R.id.settingsUserAgent)
    val xtreamUrl = dialogView.findViewById<EditText>(R.id.settingsXtreamUrl)
    val xtreamUser = dialogView.findViewById<EditText>(R.id.settingsXtreamUser)
    val xtreamPass = dialogView.findViewById<EditText>(R.id.settingsXtreamPass)
    val stalkerUrl = dialogView.findViewById<EditText>(R.id.settingsStalkerUrl)
    val stalkerMac = dialogView.findViewById<EditText>(R.id.settingsStalkerMac)
    val jellyfinUrl = dialogView.findViewById<EditText>(R.id.settingsJellyfinUrl)
    val jellyfinUser = dialogView.findViewById<EditText>(R.id.settingsJellyfinUser)
    val jellyfinPass = dialogView.findViewById<EditText>(R.id.settingsJellyfinPass)
    val jellyfinQuickConnectLabel = dialogView.findViewById<TextView>(R.id.settingsJellyfinQuickConnectLabel)
    val jellyfinQuickConnectButton = dialogView.findViewById<View>(R.id.settingsJellyfinQuickConnect)
    val plexSignInButton = dialogView.findViewById<View>(R.id.settingsPlexSignIn)
    val plexSignInLabel = dialogView.findViewById<TextView>(R.id.settingsPlexSignInLabel)
    val plexQrImage = dialogView.findViewById<ImageView>(R.id.settingsPlexQr)
    val plexCode = dialogView.findViewById<TextView>(R.id.settingsPlexCode)
    val plexStatus = dialogView.findViewById<TextView>(R.id.settingsPlexStatus)
    val hideNonEnglish = dialogView.findViewById<CheckBox>(R.id.settingsHideNonEnglish)
    val clearHistory = dialogView.findViewById<View>(R.id.settingsClearHistory)

    val iptvListSection = dialogView.findViewById<View>(R.id.settingsIptvListSection)
    val iptvProviderListContainer = dialogView.findViewById<LinearLayout>(R.id.settingsIptvProviderList)
    val iptvProviderListEmpty = dialogView.findViewById<View>(R.id.settingsIptvProviderListEmpty)
    val addIptvProviderButton = dialogView.findViewById<View>(R.id.settingsAddIptvProvider)
    val iptvFormSection = dialogView.findViewById<View>(R.id.settingsIptvFormSection)
    val iptvFieldsSection = dialogView.findViewById<View>(R.id.settingsIptvFieldsSection)
    val typePicker = dialogView.findViewById<View>(R.id.settingsTypePicker)
    val typeSummary = dialogView.findViewById<View>(R.id.settingsTypeSummary)
    val typeSummaryLabel = dialogView.findViewById<TextView>(R.id.settingsTypeSummaryLabel)
    val typeSummaryChange = dialogView.findViewById<View>(R.id.settingsTypeSummaryChange)
    val iptvFormTitle = dialogView.findViewById<TextView>(R.id.settingsIptvFormTitle)
    val iptvFormCancel = dialogView.findViewById<View>(R.id.settingsIptvFormCancel)
    val providerNameInput = dialogView.findViewById<EditText>(R.id.settingsProviderName)

    clearHistory.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sett_clear_history_confirm))
            .setMessage(getString(R.string.sett_clear_history_message))
            .setPositiveButton(getString(R.string.search_recents_clear)) { _, _ ->
                PlaybackPositionStore.clearAll(this)
                clearUpNextMemo()
                RecentlyPlayedStore.clear(this)
                Toast.makeText(this, getString(R.string.sett_history_cleared), Toast.LENGTH_SHORT).show()
                if (showingHome) selectHome()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    val dialog = MainActivity.FullScreenOverlay(
        binding.settingsContainer,
        dialogView,
        closeButton = dialogView.findViewById(R.id.settingsCancelButton),
        // Resolved lazily at show()-time (see MainActivity.FullScreenOverlay) - if nothing's
        // configured yet, openIptvForm(null) has already run by then and hidden
        // addIptvProviderButton, so fall back to the first type card instead.
        initialFocus = { if (addIptvProviderButton.visibility == View.VISIBLE) addIptvProviderButton else typeM3u }
    )

    jellyfinQuickConnectButton.setOnClickListener {
        val url = jellyfinUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it, defaultScheme = "https") }
        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.sett_enter_server_url_first), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        scope.launch {
            var lastMsg = ""
            val ok = performJellyfinQuickConnect(url) { msg -> lastMsg = msg; jellyfinQuickConnectLabel.text = msg }
            jellyfinQuickConnectLabel.text = getString(R.string.sign_in_with_quick_connect)
            if (ok) {
                
                dialog.dismiss()
                Toast.makeText(this@showProviderSettings, getString(R.string.sett_signed_in_via_quick_connect), Toast.LENGTH_SHORT).show()
                loadAllConfiguredProviders(forceRefresh = true)
            } else {
                Toast.makeText(this@showProviderSettings, lastMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Plex sign-in. Runs entirely on the TV: QR up, poll, server picker, done - there is
    // nothing to type, so this button is the whole Plex form.
    var plexSignInJob: Job? = null
    plexSignInButton.setOnClickListener {
        if (plexSignInJob?.isActive == true) return@setOnClickListener
        plexStatus.visibility = View.VISIBLE
        plexSignInJob = scope.launch {
            var lastMsg = ""
            val ok = performPlexSignIn(
                onQr = { bitmap ->
                    plexQrImage.setImageBitmap(bitmap)
                    plexQrImage.visibility = if (bitmap == null) View.GONE else View.VISIBLE
                },
                onCode = { code ->
                    plexCode.text = code.orEmpty()
                    plexCode.visibility = if (code == null) View.GONE else View.VISIBLE
                }
            ) { msg -> lastMsg = msg; plexStatus.text = msg }
            plexSignInLabel.text = getString(R.string.sign_in_with_plex)
            plexQrImage.visibility = View.GONE
            plexCode.visibility = View.GONE
            if (ok) {
                dialog.dismiss()
                Toast.makeText(this@showProviderSettings, lastMsg, Toast.LENGTH_SHORT).show()
                loadAllConfiguredProviders(forceRefresh = true)
            } else {
                Toast.makeText(this@showProviderSettings, lastMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** PIN minted while handling the phone's POST, awaiting the "plex_pin" callback that
     *  follows it on the UI thread. */
    var pendingPlexPin: PlexProvider.PinLogin? = null
    var serverRunning = false
    // One form shared by every provider type, incl. Jellyfin - it used to be a
    // separate always-visible section, but that meant asking for its server/user/pass
    // even when someone only wanted IPTV. Now it's just another type card, and only
    // its fields show once picked. IPTV types share one saved-config list
    // (IptvProviderConfig, see editingProviderId); Jellyfin is still a single fixed
    // slot under the hood (see performJellyfinSave() below), just presented the same way.
    // currentType is null until a card is tapped - the rest of the form (QR button,
    // name, type-specific fields) stays hidden until then.
    var currentType: String? = null
    var editingProviderId: String? = null
    val typeCards = mapOf(
        "m3u" to typeM3u, "xtream" to typeXtream, "stalker" to typeStalker,
        "jellyfin" to typeJellyfin, "plex" to typePlex
    )
    val typeLabels = mapOf(
        "m3u" to getString(R.string.provider_type_m3u),
        "xtream" to getString(R.string.provider_type_xtream),
        "stalker" to getString(R.string.sett_stalker_portal),
        "jellyfin" to getString(R.string.provider_type_jellyfin),
        "plex" to getString(R.string.provider_type_plex)
    )

    // Every place this form shows/hides a section, the view that was holding d-pad focus
    // can be the one going GONE - and a focused view disappearing leaves nothing focused,
    // so the d-pad stops responding entirely. requestFocus() on a view that hasn't been
    // laid out yet no-ops silently, hence the next-frame retry (same shape as
    // showEmptyState()'s focusFirstAction).
    fun focusWhenReady(target: View) {
        fun attempt(): Boolean = target.isShown && target.requestFocus()
        target.post { if (!attempt()) target.post { attempt() } }
    }

    // Collapses the 4-card type picker to a one-line "Type: X · Change" summary once
    // picked - keeping all 4 cards on screen while filling in fields pushed the QR
    // code/fields below the fold, forcing a scroll right after tapping "Show QR".
    fun selectType(type: String) {
        currentType = type
        typeCards.forEach { (t, card) ->
            card.setBackgroundResource(if (t == type) R.drawable.bg_type_option_selected else R.drawable.card_surface_background)
        }
        typePicker.visibility = View.GONE
        typeSummary.visibility = View.VISIBLE
        typeSummaryLabel.text = getString(R.string.sett_type_summary, typeLabels[type] ?: "")
        iptvFieldsSection.visibility = View.VISIBLE
        // Neither media server takes a user-facing name: each is one fixed slot, named
        // after the product.
        nameSection.visibility = if (type == "jellyfin" || type == "plex") View.GONE else View.VISIBLE
        m3uGroup.visibility = if (type == "m3u") View.VISIBLE else View.GONE
        xtreamGroup.visibility = if (type == "xtream") View.VISIBLE else View.GONE
        stalkerGroup.visibility = if (type == "stalker") View.VISIBLE else View.GONE
        jellyfinGroup.visibility = if (type == "jellyfin") View.VISIBLE else View.GONE
        plexGroup.visibility = if (type == "plex") View.VISIBLE else View.GONE
        // Stalker portals identify a device by its MAC - leave blank for user to fill.
        // Plex is deliberately not QR-eligible here, even though it is the most QR-driven type
        // of the lot: its own sign-in *is* a QR (of plex.tv/link), so offering the phone-
        // pairing QR alongside it put two different QR codes on screen at once, pointing at
        // two different places, with nothing to say which one to scan. The phone-pairing form
        // still offers Plex in its dropdown for anyone who arrives there from another type.
        val qrEligible = type in listOf("m3u", "xtream", "stalker", "jellyfin")
        showQrButton.visibility = if (qrEligible) View.VISIBLE else View.GONE
        manualDivider.visibility = if (qrEligible) View.VISIBLE else View.GONE
        // The tapped type card just went GONE (typePicker hidden above), taking focus
        // with it - see focusWhenReady.
        focusWhenReady(typeSummaryChange)
    }

    fun stopQrServer() {
        qrManager.stop()
        serverRunning = false
        qrSection.visibility = View.GONE
        qrFrame.visibility = View.GONE
        qrTimer.visibility = View.GONE
    }

    typeSummaryChange.setOnClickListener {
        if (serverRunning) stopQrServer()
        currentType = null
        typeCards.values.forEach { it.setBackgroundResource(R.drawable.card_surface_background) }
        typeSummary.visibility = View.GONE
        typePicker.visibility = View.VISIBLE
        iptvFieldsSection.visibility = View.GONE
        m3uGroup.visibility = View.GONE
        xtreamGroup.visibility = View.GONE
        stalkerGroup.visibility = View.GONE
        jellyfinGroup.visibility = View.GONE
        plexGroup.visibility = View.GONE
        // Same reasoning as in selectType() - typeSummary (holding focus) just went
        // GONE, so explicitly hand focus to the now-visible first card.
        focusWhenReady(typeM3u)
    }

    fun startQrServer(type: String) {
        if (serverRunning) return
        serverRunning = true
        qrSection.visibility = View.VISIBLE
        qrFrame.visibility = View.GONE
        qrTimer.visibility = View.GONE
        qrStatus.text = getString(R.string.sett_starting_server)

        scope.launch {
            val result = qrManager.start(providerType = type)
            if (result != null) {
                qrImage.setImageBitmap(result.qrBitmap)
                qrFrame.visibility = View.VISIBLE
                qrTimer.visibility = View.VISIBLE
                qrStatus.text = getString(R.string.sett_scan_qr)
                launch {
                    while (qrManager.result != null) {
                        val rem = (result.expiresAtMs - System.currentTimeMillis()) / 1000
                        if (rem <= 0) break
                        qrTimer.text = getString(R.string.sett_expires_in, rem / 60, rem % 60)
                        delay(1000)
                    }
                    if (serverRunning) {
                        qrTimer.text = getString(R.string.sett_expired)
                        stopQrServer()
                    }
                }
            } else {
                serverRunning = false
                qrStatus.text = getString(R.string.sett_could_not_start_server)
            }
        }
    }

    qrManager.onProviderReceived = { type, form ->
        runOnUiThread {
            qrStatus.text = getString(R.string.sett_provider_received)
            when (type) {
                "m3u" -> {
                    val url = form["m3uUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                    IptvProviderStore.upsert(prefs, IptvProviderConfig(
                        id = IptvProviderStore.newId(), type = "m3u", name = form["name"]?.takeIf { it.isNotBlank() } ?: "QR M3U",
                        enabled = true, url = url, userAgent = form["userAgent"]
                    ))
                    
                    stopQrServer()
                    dialog.dismiss()
                    loadAllConfiguredProviders(forceRefresh = true)
                }
                "xtream" -> {
                    val su = form["serverUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                    IptvProviderStore.upsert(prefs, IptvProviderConfig(
                        id = IptvProviderStore.newId(), type = "xtream", name = form["name"]?.takeIf { it.isNotBlank() } ?: "QR Xtream",
                        enabled = true, url = su, username = form["username"], password = form["password"]
                    ))
                    
                    stopQrServer()
                    dialog.dismiss()
                    loadAllConfiguredProviders(forceRefresh = true)
                }
                "stalker" -> {
                    val su = form["stalkerUrl"]?.let { normalizeServerUrl(it) } ?: return@runOnUiThread
                    val mac = form["stalkerMac"]?.takeIf { it.isNotBlank() } ?: return@runOnUiThread
                    // MAC rides in userAgent - the same slot Stalker configs use for it
                    // everywhere else (see IptvProviderConfig / loadAllConfiguredProviders).
                    IptvProviderStore.upsert(prefs, IptvProviderConfig(
                        id = IptvProviderStore.newId(), type = "stalker", name = form["name"]?.takeIf { it.isNotBlank() } ?: "QR Stalker",
                        enabled = true, url = su, userAgent = mac
                    ))
                    
                    stopQrServer()
                    dialog.dismiss()
                    loadAllConfiguredProviders(forceRefresh = true)
                }
                "jellyfin" -> {
                    // Quick Connect never reaches this branch - QrPairingManager
                    // special-cases it (needs to start the session synchronously, while
                    // still handling the phone's POST, so the code can be shown on both
                    // screens) and calls onProviderReceived with type "jellyfin_quickconnect"
                    // instead. This path is password-only.
                    val url = form["jellyfinServerUrl"]?.let { normalizeServerUrl(it, defaultScheme = "https") } ?: return@runOnUiThread
                    val user = form["jellyfinUsername"]; val pass = form["jellyfinPassword"]
                    prefs.edit().putString("jellyfin_url", url).putString("jellyfin_user", user).putString("jellyfin_pass", pass).putBoolean("jellyfin_provider_enabled", true).apply()
                    
                    stopQrServer()
                    dialog.dismiss()
                    loadAllConfiguredProviders(forceRefresh = true)
                }
                "plex_pin" -> {
                    // The PIN was minted inside onPlexPinLogin below (so the phone could show
                    // and open it); this side resumes the same sign-in from it - polling,
                    // server pick, endpoint probe - rather than starting a second one.
                    val pin = pendingPlexPin ?: return@runOnUiThread
                    pendingPlexPin = null
                    qrStatus.text = getString(R.string.sett_plex_scan_code, pin.code)
                    scope.launch {
                        var lastMsg = ""
                        // This pane has one line and no QR - the phone already carries both,
                        // having just loaded the page. While the code is unclaimed that line
                        // holds the code, because someone who walks away from the phone page
                        // still needs it; step updates take the line over once it is spent.
                        var codeShowing = true
                        val ok = performPlexSignIn(
                            existing = pin,
                            onCode = { code ->
                                codeShowing = code != null
                                if (code != null) qrStatus.text = getString(R.string.sett_plex_scan_code, code)
                            },
                            onStatus = { msg ->
                                lastMsg = msg
                                if (!codeShowing) qrStatus.text = msg
                            }
                        )
                        if (ok) {
                            stopQrServer()
                            dialog.dismiss()
                            Toast.makeText(this@showProviderSettings, lastMsg, Toast.LENGTH_SHORT).show()
                            loadAllConfiguredProviders(forceRefresh = true)
                        } else {
                            qrStatus.text = lastMsg
                        }
                    }
                }
                "jellyfin_quickconnect" -> {
                    val url = form["serverUrl"] ?: return@runOnUiThread
                    val code = form["code"] ?: return@runOnUiThread
                    val secret = form["secret"] ?: return@runOnUiThread
                    qrStatus.text = getString(R.string.sett_enter_code_on_server, code)
                    scope.launch {
                        var lastMsg = ""
                        val ok = performJellyfinQuickConnect(url, existing = code to secret) { msg -> lastMsg = msg; qrStatus.text = msg }
                        if (ok) {
                            
                            stopQrServer()
                            dialog.dismiss()
                            Toast.makeText(this@showProviderSettings, getString(R.string.sett_signed_in_via_quick_connect), Toast.LENGTH_SHORT).show()
                            loadAllConfiguredProviders(forceRefresh = true)
                        } else {
                            qrStatus.text = lastMsg
                        }
                    }
                }
            }
        }
    }

    qrManager.onPlexPinLogin = {
        // Minted here, held for the "plex_pin" branch above: both screens have to be showing
        // the same code, and the TV has to poll the very PIN the phone was sent to.
        val plex = newPlexProvider()
        val pin = withContext(Dispatchers.IO) { plex.startPinLogin() }
        if (pin != null) {
            pendingPlexPin = pin
            QrPairingManager.PlexPinStart(pin.code, pin.authUrl, null)
        } else {
            QrPairingManager.PlexPinStart(null, null, plex.lastAuthError ?: getString(R.string.sett_plex_couldnt_start))
        }
    }

    qrManager.onJellyfinQuickConnect = { url ->
        // url is already normalized by QrPairingManager before it gets here.
        val qc = JellyfinProvider(BaseApplication.instance.okHttpClient)
        val pair = withContext(Dispatchers.IO) { qc.startQuickConnect(url) }
        if (pair != null) {
            QrPairingManager.QuickConnectStart(pair.first, pair.second, null)
        } else {
            QrPairingManager.QuickConnectStart(null, null, qc.lastQuickConnectError ?: getString(R.string.sett_qc_couldnt_start))
        }
    }

    qrManager.onError = { msg ->
        runOnUiThread { qrStatus.text = msg }
    }

    typeCards.forEach { (type, card) ->
        card.setOnClickListener {
            selectType(type)
            if (serverRunning) {
                stopQrServer()
                startQrServer(type)
            }
        }
    }
    showQrButton.setOnClickListener { currentType?.let { startQrServer(it) } }

    fun closeIptvForm() {
        editingProviderId = null
        currentType = null
        typeCards.values.forEach { it.setBackgroundResource(R.drawable.card_surface_background) }
        typeSummary.visibility = View.GONE
        typePicker.visibility = View.VISIBLE
        iptvFieldsSection.visibility = View.GONE
        iptvFormSection.visibility = View.GONE
        addIptvProviderButton.visibility = View.VISIBLE
        iptvListSection.visibility = View.VISIBLE
        if (serverRunning) stopQrServer()
        // Cancel (or whatever field was focused) is inside the section just hidden.
        focusWhenReady(addIptvProviderButton)
    }

    // Adding new (existing == null) always starts on the type picker with every
    // field hidden - the type cards are the only thing shown until one is tapped.
    // Editing (existing != null) skips straight to that type's fields since it's
    // already known. The "your providers" list collapses while this is open (see
    // settingsIptvListSection) - it's irrelevant mid-add and the space it frees up
    // is what keeps the QR code/fields on screen without a scroll.
    fun openIptvForm(existing: IptvProviderConfig?) {
        editingProviderId = existing?.id
        addIptvProviderButton.visibility = View.GONE
        iptvListSection.visibility = View.GONE
        providerNameInput.setText(existing?.name ?: "")
        if (existing != null) {
            iptvFormTitle.text = getString(R.string.sett_editing_provider, existing.name)
            iptvFormTitle.visibility = View.VISIBLE
            selectType(existing.type)
        } else {
            iptvFormTitle.visibility = View.GONE
            currentType = null
            typeCards.values.forEach { it.setBackgroundResource(R.drawable.card_surface_background) }
            typeSummary.visibility = View.GONE
            typePicker.visibility = View.VISIBLE
            iptvFieldsSection.visibility = View.GONE
            m3uGroup.visibility = View.GONE
            xtreamGroup.visibility = View.GONE
            stalkerGroup.visibility = View.GONE
            jellyfinGroup.visibility = View.GONE
            plexGroup.visibility = View.GONE
        }
        val type = existing?.type ?: "m3u"
        m3uUrl.setText(if (type == "m3u") existing?.url ?: "" else "")
        uaInput.setText(if (type == "m3u") existing?.userAgent ?: "" else "")
        xtreamUrl.setText(if (type == "xtream") existing?.url ?: "" else "")
        xtreamUser.setText(if (type == "xtream") existing?.username ?: "" else "")
        xtreamPass.setText(if (type == "xtream") existing?.password ?: "" else "")
        stalkerUrl.setText(if (type == "stalker") existing?.url ?: "" else "")
        stalkerMac.setText(if (type == "stalker") existing?.userAgent ?: "" else "")
        iptvFormSection.visibility = View.VISIBLE
        // Whatever opened this ("+ Add Provider", or a list row's Edit button) just went
        // GONE with the list, so focus has to be handed to the form explicitly. The edit
        // path is already covered by selectType() above; the add path lands on the first
        // type card. Without this, adding a second provider left nothing focused at all -
        // the type cards couldn't be reached and the d-pad did nothing. First run never
        // hit it because openIptvForm(null) runs before the overlay's show(), whose
        // initialFocus falls back to typeM3u.
        if (existing == null) focusWhenReady(typeM3u)
    }

    // Jellyfin isn't in IptvProviderStore (single fixed slot, stored as loose prefs -
    // see hasJellyfinConfigured()), so editing it re-uses the same form/type-card UI
    // but pre-fills from those prefs instead of an IptvProviderConfig.
    fun openJellyfinEditForm() {
        editingProviderId = null
        addIptvProviderButton.visibility = View.GONE
        iptvListSection.visibility = View.GONE
        iptvFormTitle.text = getString(R.string.sett_editing_jellyfin)
        iptvFormTitle.visibility = View.VISIBLE
        providerNameInput.setText("")
        selectType("jellyfin")
        jellyfinUrl.setText(prefs.getString("jellyfin_url", "") ?: "")
        jellyfinUser.setText(prefs.getString("jellyfin_user", "") ?: "")
        jellyfinPass.setText(prefs.getString("jellyfin_pass", "") ?: "")
        iptvFormSection.visibility = View.VISIBLE
    }

    // Plex, like Jellyfin, isn't in IptvProviderStore (single fixed slot, loose prefs - see
    // hasPlexConfigured()). Unlike Jellyfin there is nothing to pre-fill: "editing" a Plex
    // slot means signing in again, which is also how you switch to a different server on the
    // account.
    fun openPlexEditForm() {
        editingProviderId = null
        addIptvProviderButton.visibility = View.GONE
        iptvListSection.visibility = View.GONE
        iptvFormTitle.text = getString(R.string.sett_editing_plex)
        iptvFormTitle.visibility = View.VISIBLE
        providerNameInput.setText("")
        selectType("plex")
        iptvFormSection.visibility = View.VISIBLE
    }

    fun renderIptvProviderList() {
        iptvProviderListContainer.removeAllViews()
        val list = IptvProviderStore.load(prefs)
        iptvProviderListEmpty.visibility =
            if (list.isEmpty() && !hasJellyfinConfigured() && !hasPlexConfigured()) View.VISIBLE else View.GONE
        for (cfg in list) {
            val row = layoutInflater.inflate(R.layout.item_iptv_provider_row, iptvProviderListContainer, false)
            val enabledBox = row.findViewById<CheckBox>(R.id.rowEnabled)
            enabledBox.isChecked = cfg.enabled
            // The checkbox is not clickable/focusable itself - clicking the row is what
            // toggles it, which is the only way a D-pad can reach it at all.
            row.setOnClickListener {
                val checked = !enabledBox.isChecked
                enabledBox.isChecked = checked
                IptvProviderStore.setEnabled(prefs, cfg.id, checked)
                applyProviderToggle(checked) { it.sourceProviderId == cfg.id }
            }
            // Per-content-type toggles: TV / Movies / Series, one checkbox each. Persist
            // before reloading - the reload must see the new flags, and a stale cache
            // would resurrect the types otherwise (see the cold-start filter).
            fun bindContentBox(box: CheckBox, checked: Boolean, write: (Boolean) -> Unit) {
                box.isChecked = checked
                box.setOnClickListener {
                    write(box.isChecked)
                    if (hasProviderConfigured()) scope.launch { loadAllConfiguredProviders(forceRefresh = true) }
                }
            }
            bindContentBox(row.findViewById(R.id.rowTvBox), cfg.liveEnabled) { on -> IptvProviderStore.setContentFlags(prefs, cfg.id, live = on) }
            bindContentBox(row.findViewById(R.id.rowMoviesBox), cfg.moviesEnabled) { on -> IptvProviderStore.setContentFlags(prefs, cfg.id, movies = on) }
            bindContentBox(row.findViewById(R.id.rowSeriesBox), cfg.seriesEnabled) { on -> IptvProviderStore.setContentFlags(prefs, cfg.id, series = on) }
            row.findViewById<TextView>(R.id.rowName).text = cfg.name
            val typeLabel = when (cfg.type) {
                "xtream" -> getString(R.string.provider_type_xtream)
                "stalker" -> getString(R.string.sett_stalker_portal)
                else -> getString(R.string.provider_type_m3u)
            }
            row.findViewById<TextView>(R.id.rowDetail).text = getString(R.string.sett_provider_row_detail, typeLabel, cfg.url ?: "")
            row.findViewById<View>(R.id.rowEditButton).setOnClickListener { openIptvForm(cfg) }
            row.findViewById<View>(R.id.rowRemoveButton).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sett_remove_provider_confirm, cfg.name))
                    .setMessage(getString(R.string.sett_remove_provider_message))
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        IptvProviderStore.remove(prefs, cfg.id)
                        renderIptvProviderList()
                        // The removed row's own Remove button was holding focus and is
                        // gone now - see focusWhenReady.
                        focusWhenReady(addIptvProviderButton)
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            iptvProviderListContainer.addView(row)
        }
        if (hasJellyfinConfigured()) {
            val row = layoutInflater.inflate(R.layout.item_iptv_provider_row, iptvProviderListContainer, false)
            val enabledBox = row.findViewById<CheckBox>(R.id.rowEnabled)
            enabledBox.isChecked = isJellyfinEnabled()
            row.setOnClickListener {
                val checked = !enabledBox.isChecked
                enabledBox.isChecked = checked
                prefs.edit().putBoolean("jellyfin_provider_enabled", checked).apply()
                applyProviderToggle(checked) { it.isJellyfin }
            }
            // Per-content-type toggles for the Jellyfin slot (loose prefs, no config object).
            fun bindJellyfinBox(box: CheckBox, key: String, checked: Boolean) {
                box.isChecked = checked
                box.setOnClickListener {
                    prefs.edit().putBoolean(key, box.isChecked).apply()
                    if (hasProviderConfigured()) scope.launch { loadAllConfiguredProviders(forceRefresh = true) }
                }
            }
            bindJellyfinBox(row.findViewById(R.id.rowTvBox), "jellyfin_live_enabled", jellyfinAllowsLive())
            // Read the STORED flags (like the provider rows), not the effective gates:
            // the effective gates fold in the global VOD switch, so a provider that
            // allows movies while the app-level gate is on would otherwise render its
            // Movies box unchecked and look broken.
            bindJellyfinBox(row.findViewById(R.id.rowMoviesBox), "jellyfin_movies_enabled", jellyfinFlag("jellyfin_movies_enabled"))
            bindJellyfinBox(row.findViewById(R.id.rowSeriesBox), "jellyfin_series_enabled", jellyfinFlag("jellyfin_series_enabled"))
            row.findViewById<TextView>(R.id.rowName).text = getString(R.string.provider_type_jellyfin)
            row.findViewById<TextView>(R.id.rowDetail).text = getString(R.string.sett_provider_row_detail, getString(R.string.provider_type_jellyfin), prefs.getString("jellyfin_url", "") ?: "")
            row.findViewById<View>(R.id.rowEditButton).setOnClickListener { openJellyfinEditForm() }
            row.findViewById<View>(R.id.rowRemoveButton).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sett_remove_jellyfin_confirm))
                    .setMessage(getString(R.string.sett_remove_jellyfin_message))
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        prefs.edit().remove("jellyfin_url").remove("jellyfin_user").remove("jellyfin_pass")
                            .remove("jellyfin_provider_enabled").remove("jellyfin_disable_vod")
                            .remove("jellyfin_live_enabled").remove("jellyfin_movies_enabled").remove("jellyfin_series_enabled").apply()
                        renderIptvProviderList()
                        focusWhenReady(addIptvProviderButton)
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            iptvProviderListContainer.addView(row)
        }
        if (hasPlexConfigured()) {
            val row = layoutInflater.inflate(R.layout.item_iptv_provider_row, iptvProviderListContainer, false)
            val enabledBox = row.findViewById<CheckBox>(R.id.rowEnabled)
            enabledBox.isChecked = isPlexEnabled()
            row.setOnClickListener {
                val checked = !enabledBox.isChecked
                enabledBox.isChecked = checked
                prefs.edit().putBoolean("plex_provider_enabled", checked).apply()
                applyProviderToggle(checked) { it.isPlex }
            }
            fun bindPlexBox(box: CheckBox, key: String, checked: Boolean) {
                box.isChecked = checked
                box.setOnClickListener {
                    prefs.edit().putBoolean(key, box.isChecked).apply()
                    if (hasProviderConfigured()) scope.launch { loadAllConfiguredProviders(forceRefresh = true) }
                }
            }
            // No TV box: the Plex slot never produces live channels (Plex Live TV is a
            // tuner-session flow Lumora's live model can't express), so a checkbox for it
            // would toggle nothing. Disabled rather than hidden, so the row still lines up
            // with its siblings' three columns.
            val tvBox = row.findViewById<CheckBox>(R.id.rowTvBox)
            tvBox.isChecked = false
            tvBox.isEnabled = false
            // Read the STORED flags (like the provider rows), not the effective gates: the
            // effective gates fold in the global VOD switch, so a slot that allows movies
            // while the app-level gate is on would render its Movies box unchecked and look
            // broken.
            bindPlexBox(row.findViewById(R.id.rowMoviesBox), "plex_movies_enabled", prefs.getBoolean("plex_movies_enabled", true))
            bindPlexBox(row.findViewById(R.id.rowSeriesBox), "plex_series_enabled", prefs.getBoolean("plex_series_enabled", true))
            row.findViewById<TextView>(R.id.rowName).text = getString(R.string.provider_type_plex)
            row.findViewById<TextView>(R.id.rowDetail).text = getString(
                R.string.sett_provider_row_detail,
                getString(R.string.provider_type_plex),
                prefs.getString("plex_server_name", null) ?: prefs.getString("plex_url", "") ?: ""
            )
            row.findViewById<View>(R.id.rowEditButton).setOnClickListener { openPlexEditForm() }
            row.findViewById<View>(R.id.rowRemoveButton).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sett_remove_plex_confirm))
                    .setMessage(getString(R.string.sett_remove_plex_message))
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        // plex_client_id deliberately survives: it identifies this install to
                        // plex.tv, and minting a new one on every remove/re-add would litter
                        // the user's Plex device list with a fresh entry each time.
                        prefs.edit().remove("plex_url").remove("plex_token")
                            .remove("plex_account_token").remove("plex_server_name")
                            .remove("plex_provider_enabled")
                            .remove("plex_movies_enabled").remove("plex_series_enabled").apply()
                        plexClient = null
                        plexResumeItems = emptyList()
                        plexNextUpItems = emptyList()
                        renderIptvProviderList()
                        focusWhenReady(addIptvProviderButton)
                        loadAllConfiguredProviders(forceRefresh = true)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            iptvProviderListContainer.addView(row)
        }
    }

    addIptvProviderButton.setOnClickListener { openIptvForm(null) }

    iptvFormCancel.setOnClickListener { closeIptvForm() }

    renderIptvProviderList()
    // Exposed so the plugin-discovery pane can refresh this list after adding a provider.
    refreshIptvProviderList = { renderIptvProviderList() }
    // First run, nothing configured at all yet - the empty list + tiny "+ Add" button
    // would leave the user staring at nothing to interact with, so open the form
    // immediately (matches the old single-slot behavior of showing fields right away).
    if (IptvProviderStore.load(prefs).isEmpty() && !hasJellyfinConfigured() && !hasPlexConfigured()) {
        openIptvForm(null)
    }

    // Backup & Restore
    val backupManager = BackupManager(this)
    dialogView.findViewById<View>(R.id.settingsExportBackup).setOnClickListener {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, "lumora_backup.json")
        }
        try {
            startActivityForResult(intent, MainActivity.REQUEST_EXPORT_BACKUP)
            pendingBackupManager = backupManager
        } catch (e: android.content.ActivityNotFoundException) {
            // Fire TV and most Android TV boxes ship no document picker at all - SAF
            // just isn't there to launch. Fall back to a fixed app-storage location
            // that works on every device, no picker required.
            scope.launch {
                val file = localBackupFile()
                val success = withContext(Dispatchers.IO) { backupManager.exportTo(Uri.fromFile(file)) }
                Toast.makeText(
                    this@showProviderSettings,
                    if (success) getString(R.string.sett_backup_saved_to, file.absolutePath) else getString(R.string.sett_export_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    dialogView.findViewById<View>(R.id.settingsImportBackup).setOnClickListener {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            startActivityForResult(intent, MainActivity.REQUEST_IMPORT_BACKUP)
            pendingBackupManager = backupManager
        } catch (e: android.content.ActivityNotFoundException) {
            val file = localBackupFile()
            if (!file.exists()) {
                Toast.makeText(this@showProviderSettings, getString(R.string.sett_no_backup_file, file.absolutePath), Toast.LENGTH_LONG).show()
            } else {
                scope.launch {
                    // Import is an explicit user action, so existing data isn't a decision
                    // point - auto-confirm on the first pass's conflict result (see the
                    // same pattern in MainActivity.REQUEST_IMPORT_BACKUP) so the import
                    // actually applies instead of silently reporting 0 imported.
                    var result = withContext(Dispatchers.IO) { backupManager.importFrom(Uri.fromFile(file)) }
                    if (result.conflicts > 0 && result.providersImported == 0 && result.epgSourcesImported == 0) {
                        result = withContext(Dispatchers.IO) { backupManager.importFrom(Uri.fromFile(file), confirmed = true) }
                    }
                    Toast.makeText(
                        this@showProviderSettings,
                        getString(R.string.sett_imported_summary, result.providersImported, result.epgSourcesImported, result.customGroupsImported),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // EPG Source
    dialogView.findViewById<View>(R.id.settingsAddEpgSource).setOnClickListener {
        showEpgSourceListDialog()
    }

    // Recording storage
    dialogView.findViewById<View>(R.id.settingsRecordingStorage).setOnClickListener {
        Toast.makeText(this, getString(R.string.sett_recording_storage_path, "${filesDir}/recordings"), Toast.LENGTH_SHORT).show()
    }

    // Playback settings. Only entries that change something belong here — the Decoder /
    // Buffer / Surface / FFmpeg rows this dialog used to carry wrote prefs no part of the
    // playback stack ever read (Surface didn't even write one, it toasted "Surface mode
    // changed" and returned), so every one of them was a control that reported success and
    // did nothing.
    dialogView.findViewById<View>(R.id.settingsDecoderMode).setOnClickListener {
        val items = arrayOf(
            getString(R.string.sett_external_player_summary, externalPlayerSummary(this)),
            getString(R.string.sett_suggest_external_player_on_problems, if (prefs.getBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, true)) getString(R.string.sett_on) else getString(R.string.sett_off)),
            getString(R.string.legal_notice_title)
        )
        AlertDialog.Builder(this@showProviderSettings)
            .setTitle(getString(R.string.sett_playback_settings_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseDefaultExternalPlayer()
                    1 -> {
                        val on = !prefs.getBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, true)
                        prefs.edit().putBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, on).apply()
                        Toast.makeText(this@showProviderSettings, getString(R.string.sett_suggest_external_player, if (on) getString(R.string.sett_on) else getString(R.string.sett_off)), Toast.LENGTH_SHORT).show()
                    }
                    2 -> showLegalNotice()
                }
            }
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    // A/V sync offset settings
    dialogView.findViewById<View>(R.id.settingsAvOffset).setOnClickListener {
        val current = avOffsetManager.getOffset()
        val presets = listOf("-500 ms", "-250 ms", "-100 ms", "-50 ms", "0 ms", "+50 ms", "+100 ms", "+250 ms", "+500 ms")
        val values = listOf(-500, -250, -100, -50, 0, 50, 100, 250, 500)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this@showProviderSettings)
            .setTitle(getString(R.string.sett_av_sync_offset_title))
            .setSingleChoiceItems(presets.toTypedArray(), checked) { dialog, which ->
                avOffsetManager.save(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Jellyfin is a single independent slot (unlike IPTV, which is a list managed via
    // openIptvForm()/renderIptvProviderList() above), so its fields still pre-fill directly.
    jellyfinUrl.setText(prefs.getString("jellyfin_url", ""))
    jellyfinUser.setText(prefs.getString("jellyfin_user", ""))

    val subscriptionStatus = dialogView.findViewById<TextView>(R.id.settingsSubscriptionStatus)
    val expDate = prefs.getString("xtream_exp_date", null)?.toLongOrNull()
    val isTrial = prefs.getBoolean("xtream_is_trial", false)
    formatSubscriptionStatus(expDate, isTrial)?.let { status ->
        subscriptionStatus.text = status
        subscriptionStatus.setTextColor(getColor(if (status.startsWith("⚠")) R.color.live_red else R.color.success_green))
        subscriptionStatus.visibility = View.VISIBLE
    }

    hideNonEnglish.isChecked = prefs.getBoolean(PREF_HIDE_NON_ENGLISH, true)
    hideNonEnglish.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(PREF_HIDE_NON_ENGLISH, checked).apply()
        if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
    }

    val hideAdult = dialogView.findViewById<CheckBox>(R.id.settingsHideAdult)
    val parentalPinRow = dialogView.findViewById<View>(R.id.settingsParentalPin)
    val parentalPinLabel = dialogView.findViewById<TextView>(R.id.settingsParentalPinLabel)
    hideAdult.isChecked = prefs.getBoolean(PREF_HIDE_ADULT, true)
    parentalPinLabel.text = if (hasParentalPin()) getString(R.string.sett_change_parental_pin) else getString(R.string.set_parental_pin)

    lateinit var hideAdultListener: CompoundButton.OnCheckedChangeListener
    fun applyHideAdult(checked: Boolean) {
        hideAdult.setOnCheckedChangeListener(null)
        hideAdult.isChecked = checked
        hideAdult.setOnCheckedChangeListener(hideAdultListener)
        prefs.edit().putBoolean(PREF_HIDE_ADULT, checked).apply()
        if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
    }
    hideAdultListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        // Turning filtering ON is always allowed. Turning it OFF needs the PIN, if one
        // is set - otherwise the toggle is just a preference with nothing locking it.
        if (!checked && hasParentalPin()) {
            applyHideAdult(true)
            promptForPin(getString(R.string.sett_enter_pin_show_adult)) { applyHideAdult(false) }
        } else {
            applyHideAdult(checked)
        }
    }
    hideAdult.setOnCheckedChangeListener(hideAdultListener)
    parentalPinRow.setOnClickListener {
        if (hasParentalPin()) {
            promptForPin(getString(R.string.sett_enter_current_pin)) { showSetPinDialog(parentalPinLabel) }
        } else {
            showSetPinDialog(parentalPinLabel)
        }
    }

    val categorizeLive = dialogView.findViewById<CheckBox>(R.id.settingsCategorizeLive)
    categorizeLive.isChecked = prefs.getBoolean(PREF_CATEGORIZE_LIVE, true)
    categorizeLive.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(PREF_CATEGORIZE_LIVE, checked).apply()
        if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
    }
    val categorizeVod = dialogView.findViewById<CheckBox>(R.id.settingsCategorizeVod)
    categorizeVod.isChecked = prefs.getBoolean(PREF_CATEGORIZE_VOD, true)
    categorizeVod.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(PREF_CATEGORIZE_VOD, checked).apply()
        if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
    }
    val groupChannelsBox = dialogView.findViewById<CheckBox>(R.id.settingsGroupChannels)
    groupChannelsBox.isChecked = prefs.getBoolean(PREF_GROUP_CHANNELS, true)
    groupChannelsBox.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(PREF_GROUP_CHANNELS, checked).apply()
        if (allChannels.isNotEmpty()) scope.launch { classifyAndShow() }
    }

    // Dub playback preferences: prefer dub-flagged search results, and keep the
    // sideloaded subtitles on when a stream plays back with its dubbed audio track.
    // Both default off; the subtitles one is read by PlayerManager from the same prefs.
    val filtersPane = dialogView.findViewById<LinearLayout>(R.id.paneFilters)
    filtersPane.addView(dubCheckBoxRow(
        getString(R.string.sett_prefer_dubbed_audio),
        getString(R.string.sett_prefer_dubbed_audio_caption),
        PREF_PREFER_DUB_AUDIO
    ))
    filtersPane.addView(dubCheckBoxRow(
        getString(R.string.sett_subtitles_with_dub),
        getString(R.string.sett_subtitles_with_dub_caption),
        PREF_SUBTITLES_WITH_DUB
    ))
    filtersPane.addView(dubCheckBoxRow(
        getString(R.string.subtitles),
        getString(R.string.sett_subtitles_caption),
        PREF_SUBTITLES_ENABLED
    ))

    // General pane: Simple mode + Disable VOD live here, not under Filters - they shape
    // the whole app (which tabs exist, what gets fetched), not the catalogue filters.
    val generalPane = dialogView.findViewById<LinearLayout>(R.id.paneGeneral)
    lateinit var vodCheckBox: CheckBox
    generalPane.addView(dubCheckBoxRow(
        getString(R.string.sett_simple_mode),
        getString(R.string.sett_simple_mode_caption),
        PREF_SIMPLE_MODE
    ) { checked ->
        // Simple mode drives the VOD toggle so the two checkboxes never disagree:
        // on -> VOD disabled (box checked), off -> VOD re-enabled (box unchecked).
        prefs.edit().putBoolean(PREF_DISABLE_VOD, checked).apply()
        vodCheckBox.isChecked = checked
        applySimpleModeUi()
        // Simple mode forces VOD off, so its effective state changed with the toggle.
        vodStateChanged()
    })
    vodCheckBox = dubCheckBoxRow(
        getString(R.string.sett_disable_vod),
        getString(R.string.sett_disable_vod_caption),
        PREF_DISABLE_VOD
    ) { vodStateChanged() }
    generalPane.addView(vodCheckBox)
    generalPane.addView(
        languageChoiceRow(
            getString(R.string.settings_ui_language),
            PREF_UI_LANGUAGE,
            getString(R.string.settings_ui_language_caption),
            choices = UI_LANGUAGES,
            onSelect = { code -> onUiLanguageSelected(code) }
        )
    )
    generalPane.addView(
        languageChoiceRow(
            getString(R.string.sett_audio_language),
            PREF_AUDIO_LANGUAGE,
            getString(R.string.sett_audio_language_caption)
        )
    )
    generalPane.addView(
        languageChoiceRow(
            getString(R.string.sett_subtitle_language),
            PREF_SUBTITLE_LANGUAGE,
            getString(R.string.sett_subtitle_language_caption)
        )
    )

    // StreamVault-style nav rail: one section visible at a time.
    val navRows = listOf(
        R.id.navProviders to R.id.paneProviders,
        R.id.navPlayback to R.id.panePlayback,
        R.id.navFilters to R.id.paneFilters,
        R.id.navPrivacy to R.id.panePrivacy,
        R.id.navBackup to R.id.paneBackup,
        R.id.navEpg to R.id.paneEpg,
        R.id.navDownloads to R.id.paneDownloads,
        R.id.navPlugins to R.id.panePlugins,
        R.id.navGeneral to R.id.paneGeneral,
        R.id.navAbout to R.id.paneAbout,
        // Appended rather than slotted in next to Plugins, where it sits visually: selectSection()
        // addresses these by list index, so inserting in the middle would silently renumber every
        // section below it - including the hardcoded selectSection(7) the plugin rail rows use.
        R.id.navSites to R.id.paneSites
    ).map { (navId, paneId) -> dialogView.findViewById<View>(navId) to dialogView.findViewById<View>(paneId) }
    // Last section chosen - the rail's re-expand pill refocuses it (mirrors the category
    // rail refocusing the previously selected row).
    var activeSection = 0
    fun selectSection(index: Int) {
        activeSection = index
        navRows.forEachIndexed { i, (row, pane) ->
            row.isSelected = i == index
            pane.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        // A plugin's page is not one of these panes and would otherwise stay up underneath
        // whichever section was just chosen. Selecting Plugins itself is handled by the
        // rail's own listener, which opens either the list or a specific plugin's page.
        openPluginId = null
        dialogView.findViewById<View>(R.id.panePluginDetail)?.visibility = View.GONE
        // Reachable from code, not just a rail click (e.g. onProviderAdded() jumping here
        // after a plugin candidate is added) - without this the D-pad's focus is left on
        // whatever view triggered the jump, which has often just been removed from the
        // tree by the same re-render, leaving nothing focused and the remote stuck.
        // With the rail collapsed the row is gone - leave focus where it is (the expand
        // pill) rather than requesting focus on a GONE view, which silently does nothing.
        if (!isSettingsRailCollapsed()) navRows[index].first.requestFocus()
    }
    navRows.forEachIndexed { i, (row, _) -> row.setOnClickListener { selectSection(i) } }

    // Collapse/expand of the rail itself, mirroring the category sidebar: a "Collapse" row
    // at the top of the rail hides it, and the floating pill that replaces it brings it
    // back and refocuses the section that was selected.
    dialogView.findViewById<View>(R.id.navCollapseRail).setOnClickListener { collapseSettingsRail() }
    val settingsExpandRailButton = dialogView.findViewById<View>(R.id.settingsExpandRailButton)
    settingsExpandRailButton.setOnClickListener {
        // Portrait's auto-hide is transient state, not the pref - see isSettingsRailCollapsed().
        if (isPortraitPhone()) portraitSettingsRailExpanded = true
        else prefs.edit().putBoolean(PREF_SETTINGS_RAIL_COLLAPSED, false).apply()
        applySettingsRailVisibility()
        // Rows aren't laid out the instant the rail becomes visible again - retry once on
        // the next frame (same double-post pattern as the category rail's re-expand).
        val row = navRows[activeSection].first
        settingsExpandRailButton.post {
            if (!row.isShown || !row.requestFocus()) {
                settingsExpandRailButton.post { row.requestFocus() }
            }
        }
    }
    // Single canonical apply point: the tree is inflated fresh on every open, so the
    // collapsed state (persisted pref, or a portrait phone's transient auto-hide) is
    // applied here, before the first selectSection so nothing requests focus on a row
    // that is about to disappear. Re-applied on rotation from onConfigurationChanged.
    // dialogView is passed because activeSettingsOverlay is not assigned until show().
    applySettingsRailVisibility(dialogView)
    selectSection(0)

    // A TV box has nowhere meaningful to browse a downloaded file (same reasoning
    // as the Downloads tab being mobile-only).
    dialogView.findViewById<View>(R.id.navDownloads).visibility = if (isTv) View.GONE else View.VISIBLE

    // Downloads pane reuses the exact same adapter/data as the Downloads tab -
    // RecyclerView supports multiple views sharing one adapter instance fine.
    dialogView.findViewById<RecyclerView>(R.id.settingsDownloadsList).apply {
        layoutManager = LinearLayoutManager(this@showProviderSettings)
        adapter = downloadAdapter
    }
    val settingsDownloadsEmptyText = dialogView.findViewById<TextView>(R.id.settingsDownloadsEmptyText)
    if (!isTv) {
        scope.launch {
            val records = withContext(Dispatchers.IO) { DownloadStore.getAll(this@showProviderSettings) }
            settingsDownloadsEmptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    refreshDownloadsList()

    wirePluginsPane(dialogView) { selectSection(1) }
    // After wirePluginsPane: the child rows drive the pane through revealPluginInPane,
    // with the plugin list itself left at its previous section.
    wirePluginNavRows(dialogView) { selectSection(7) }

    wireScraperSettingsPane(dialogView)

    // About pane
    dialogView.findViewById<TextView>(R.id.settingsAppVersion).text = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (e: Exception) { getString(R.string.sett_unknown) }
    dialogView.findViewById<View>(R.id.settingsGithubLink).setOnClickListener {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://github.com/disclosurez/Lumora")))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.sett_no_browser_available), Toast.LENGTH_SHORT).show()
        }
    }
    dialogView.findViewById<View>(R.id.settingsDiscordLink).setOnClickListener {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://discord.gg/lumora")))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.sett_no_browser_available), Toast.LENGTH_SHORT).show()
        }
    }
    val checkUpdateLabel = dialogView.findViewById<TextView>(R.id.settingsCheckUpdateLabel)
    dialogView.findViewById<View>(R.id.settingsCheckUpdate).setOnClickListener {
        checkUpdateLabel.text = getString(R.string.sett_checking)
        scope.launch {
            val updater = AppUpdateChecker(this@showProviderSettings)
            val info = withContext(Dispatchers.IO) { updater.checkForUpdate() }
            checkUpdateLabel.text = getString(R.string.check_for_updates)
            when {
                info == null -> Toast.makeText(this@showProviderSettings, getString(R.string.sett_couldnt_check_updates), Toast.LENGTH_SHORT).show()
                info.isUpdateAvailable && info.downloadUrl.isNotBlank() -> {
                    AlertDialog.Builder(this@showProviderSettings)
                        .setTitle(getString(R.string.sett_update_available))
                        .setMessage(getString(R.string.sett_update_available_message, info.latestVersion, info.currentVersion, info.releaseNotes.take(200).replace("%", "%%")))
                        .setPositiveButton(getString(R.string.update)) { _, _ -> downloadAndInstallUpdate(info.downloadUrl, info.latestVersion) }
                        .setNegativeButton(getString(R.string.sett_later), null)
                        .show()
                }
                else -> Toast.makeText(this@showProviderSettings, getString(R.string.sett_latest_version), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Init UI
    // No selectType() call here - the form starts closed with no type chosen; see
    // openIptvForm()/closeIptvForm() above for how that gets set per add/edit.
    // Hide whatever the active tab is showing so it doesn't render doubled-up behind
    // Settings in the same weight=1 slot - restored on dismiss below. That includes
    // Home's search bar and the Discover pane, which sit outside homeContent/contentRow
    // and so used to stay on screen above Settings as if they belonged to it.
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.discoverContent.visibility = View.GONE
    binding.contentRow.visibility = View.GONE
    binding.emptyState.visibility = View.GONE
    dialog.setOnDismissListener {
        qrManager.stop()
        // A discovery run only exists to fill in this pane - closing Settings unbinds the
        // plugin rather than leaving another app's service bound with nowhere to report.
        pluginDiscoveryJob?.cancel()
        pluginDiscoveryJob = null
        activeSettingsOverlay = null
        applyStatus()
        refreshIptvProviderList = {}
        // Both close over views in the dismissed dialog - holding them past this leaks the
        // whole inflated settings tree and would touch detached views on the next call.
        revealPluginInPane = null
        refreshPluginNavRows = null
        closeOpenPluginPage = null
        openPluginId = null
        liveDiscoveryStatusView = null
        liveDiscoveryCandidateList = null
        liveDiscoveryPlugin = null
        // The tab bar and search are gated on there being something to browse, and
        // classifyAndShow() deliberately skips that check while this overlay is up (it
        // would flip the chrome underneath the dialog). Adding a provider or switching a
        // plugin on is exactly what changes the answer, so re-derive it here - on the
        // non-empty path nothing else did, and the tab bar stayed hidden until the app was
        // restarted. showEmptyState() runs it itself on the other branch.
        //
        // "Nothing to show" is the same question classifyAndShow() asks, and it counts an
        // enabled stream_search plugin as content: a torrent or anime plugin contributes no
        // catalog entries of its own but makes Discover and Find Stream usable. Testing
        // allChannels alone sent a plugin-only setup back to the "no provider" empty state
        // the moment Settings closed, however many plugins had just been switched on.
        val hasPlugin = hasProviderlessSource()
        // Unticking the last provider (or the last plugin) has to take the tab bar and
        // search back down, and land on the empty state - which is the only screen left
        // with a way back into Settings. Asked of the enabled providers rather than of
        // allChannels: disabling one drops its items, but a provider whose channels are
        // still in memory from a cache load would otherwise keep the chrome up with
        // nothing enabled behind it.
        // Saving a provider starts its fetch and leaves Settings open; closing before that
        // fetch lands used to read as "returned nothing" and put the empty state up, which
        // is the screen the user then sat on. An in-flight load has not returned anything
        // yet - let the tab render empty under the "Loading..." status and let the load's
        // own classifyAndShow fill it in.
        val loadInFlight = providerLoadJob?.isActive == true
        if (!hasProviderEnabled() && !hasPlugin) {
            showEmptyState()
        } else if (allChannels.isEmpty() && !hasPlugin && !loadInFlight) {
            // Enabled, but it returned nothing (fetch failed, or an empty catalogue).
            showEmptyState()
        } else {
            binding.emptyState.visibility = View.GONE
            updateTopChromeVisibility()
            if (showingHome) selectHome() else if (showingDiscover) selectDiscover() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
        }
    }
    activeSettingsOverlay = dialog
    // The overlay takes the slot now; a load still narrating into it must come down.
    applyStatus()
    dialog.show()

    // The Save button's listener validates and keeps the form open on error instead of
    // dismissing unconditionally. Only acts when the add/edit form is actually open -
    // the same footer button is shared by every nav pane, most of which have nothing
    // for it to save.
    dialogView.findViewById<View>(R.id.settingsSaveButton).setOnClickListener {
        // Save is a footer button shown on every pane, but only the provider add/edit form
        // has anything to commit - everything else (toggles, pickers, PIN) persists as it is
        // changed. It used to return here silently, so on the provider list, or on Playback
        // or Filters or Plugins, pressing Save did nothing whatsoever and looked broken.
        // Closing is what Save means once the work is already saved.
        if (iptvFormSection.visibility != View.VISIBLE) {
            activeSettingsOverlay?.dismiss()
            return@setOnClickListener
        }
        if (currentType == null) {
            Toast.makeText(this, getString(R.string.sett_choose_provider_type_first), Toast.LENGTH_SHORT).show(); return@setOnClickListener
        }
        val name = providerNameInput.text.toString().trim()
        val id = editingProviderId ?: IptvProviderStore.newId()
        // The form has no per-type controls; preserve the provider's existing content
        // flags so saving the form never silently resets a movies/series split.
        val prevConfig = editingProviderId?.let { pid -> IptvProviderStore.load(prefs).firstOrNull { it.id == pid } }
        when (currentType) {
            "m3u" -> {
                val url = m3uUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                if (url.isBlank()) {
                    Toast.makeText(this, getString(R.string.sett_enter_m3u_url), Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                IptvProviderStore.upsert(prefs, IptvProviderConfig(
                    id = id, type = "m3u", name = name.ifBlank { "M3U/M3U8 Playlist" }, enabled = true,
                    liveEnabled = prevConfig?.liveEnabled ?: true,
                    moviesEnabled = prevConfig?.moviesEnabled ?: true, seriesEnabled = prevConfig?.seriesEnabled ?: true,
                    url = url, userAgent = uaInput.text.toString().trim().ifBlank { null }
                ))
            }
            "xtream" -> {
                val url = xtreamUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                if (url.isBlank()) { Toast.makeText(this, getString(R.string.sett_enter_server_url), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                IptvProviderStore.upsert(prefs, IptvProviderConfig(
                    id = id, type = "xtream", name = name.ifBlank { "Xtream" }, enabled = true,
                    liveEnabled = prevConfig?.liveEnabled ?: true,
                    moviesEnabled = prevConfig?.moviesEnabled ?: true, seriesEnabled = prevConfig?.seriesEnabled ?: true,
                    url = url, username = xtreamUser.text.toString().trim(), password = xtreamPass.text.toString().trim()
                ))
            }
            "stalker" -> {
                val url = stalkerUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it) }
                if (url.isBlank()) { Toast.makeText(this, getString(R.string.sett_enter_server_url), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                IptvProviderStore.upsert(prefs, IptvProviderConfig(
                    id = id, type = "stalker", name = name.ifBlank { "Stalker Portal" }, enabled = true,
                    liveEnabled = prevConfig?.liveEnabled ?: true,
                    moviesEnabled = prevConfig?.moviesEnabled ?: true, seriesEnabled = prevConfig?.seriesEnabled ?: true,
                    url = url, userAgent = stalkerMac.text.toString().trim()
                ))
            }
            "jellyfin" -> {
                // Not part of IptvProviderStore - Jellyfin is still a single fixed
                // slot under the hood, stored as loose prefs (see hasJellyfinConfigured()).
                val url = jellyfinUrl.text.toString().trim().let { if (it.isBlank()) it else normalizeServerUrl(it, defaultScheme = "https") }
                if (url.isBlank()) { Toast.makeText(this, getString(R.string.sett_enter_server_url), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                prefs.edit()
                    .putString("jellyfin_url", url)
                    .putString("jellyfin_user", jellyfinUser.text.toString().trim())
                    .putString("jellyfin_pass", jellyfinPass.text.toString().trim())
                    .putBoolean("jellyfin_provider_enabled", true)
                    .apply()
            }
            "plex" -> {
                // Nothing to save: the Plex slot is written by the sign-in itself (see
                // performPlexSignIn), because a Plex account - not a typed URL - is what
                // says which servers exist and what token opens them. Save with no session
                // yet would silently create a half-configured slot, so say so instead.
                if (!hasPlexConfigured()) {
                    Toast.makeText(this, getString(R.string.sign_in_with_plex), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
        }
        
        closeIptvForm()
        renderIptvProviderList()
        // The tab bar and search are gated on there being an enabled provider, and saving
        // the first one is what changes that answer. Nothing recomputed it until Settings
        // was dismissed, so the toolbar behind the dialog kept showing the no-provider
        // chrome (Settings + Refresh alone) after a successful save.
        updateTopChromeVisibility()
        Toast.makeText(this, getString(R.string.sett_provider_saved_loading), Toast.LENGTH_SHORT).show()
        loadAllConfiguredProviders(forceRefresh = true)
    }
}

// ── Parental PIN ───────────────────────────────

/** Shows the detail screen's "Find Stream" button when a stream-search plugin is enabled,
 *  and only for movies - a series detail screen isn't a single episode, so there's nothing
 *  specific to resolve from here (per-episode search would hang off the episode list). */

internal fun MainActivity.hasParentalPin(): Boolean = !prefs.getString(PREF_PARENTAL_PIN, null).isNullOrBlank()

/** 4-digit PIN entry. Calls onCorrect only if it matches the saved PIN. */
internal fun MainActivity.promptForPin(title: String, onCorrect: () -> Unit) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton(getString(R.string.sett_ok)) { _, _ ->
            if (input.text.toString() == prefs.getString(PREF_PARENTAL_PIN, null)) {
                onCorrect()
            } else {
                Toast.makeText(this, getString(R.string.sett_incorrect_pin), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/** Sets (or changes) the 4-digit PIN - entered twice so a typo doesn't lock the user out. */
internal fun MainActivity.showSetPinDialog(label: TextView) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        hint = getString(R.string.sett_pin_hint)
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.set_parental_pin))
        .setView(input)
        .setPositiveButton(getString(R.string.sett_next)) { _, _ ->
            val pin = input.text.toString()
            if (pin.length != 4) {
                Toast.makeText(this, getString(R.string.sett_pin_length_error), Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val confirm = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                hint = getString(R.string.sett_confirm_pin_hint)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sett_confirm_pin_title))
                .setView(confirm)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    if (confirm.text.toString() == pin) {
                        prefs.edit().putString(PREF_PARENTAL_PIN, pin).apply()
                        label.text = getString(R.string.sett_change_parental_pin)
                        Toast.makeText(this, getString(R.string.sett_parental_pin_set), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.sett_pins_didnt_match), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/** The full disclaimer. The car screen shows a condensed version at the start of every
 *  session (see auto/CarDisclaimerScreen.kt); this is the readable-at-leisure copy. */
internal fun MainActivity.showLegalNotice() {
    AlertDialog.Builder(this)
        .setTitle(R.string.legal_notice_title)
        .setMessage(R.string.legal_notice)
        .setPositiveButton(getString(R.string.close), null)
        .show()
}
