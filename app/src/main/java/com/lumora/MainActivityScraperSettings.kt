package com.lumora

import android.animation.AnimatorInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.lumora.player.PlayerManager
import com.lumora.scraper.bridge.ScraperCatalog
import com.lumora.scraper.bridge.ScraperSiteStore
import com.lumora.scraper.utils.DnsResolver
import com.lumora.scraper.utils.UserPreferences

/**
 * The Streaming sites settings pane.
 *
 * These are stream *sources*, not a catalogue - browsing stays on Discover/TMDB. So the pane is
 * a source list: a master switch, the two network settings that decide whether the sites are
 * reachable at all, and then one checkbox per site.
 *
 * Built in code rather than XML because the list comes from the provider registry at runtime and
 * is around seventy rows long; laying that out by hand would be seventy blocks of markup that go
 * stale the moment a site is added or dropped.
 */
internal fun MainActivity.wireScraperSettingsPane(root: View) {
    val host = root.findViewById<LinearLayout>(R.id.settingsSitesRows) ?: return
    host.removeAllViews()

    val siteRows = mutableListOf<CheckBox>()

    fun applyEnabledState(enabled: Boolean) {
        // Every row below the master switch is meaningless while it is off. Disabled rather than
        // hidden, so the pane does not visibly collapse and reflow under the user's focus.
        siteRows.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    host.addView(
        scraperToggleRow(
            title = getString(R.string.scraper_enabled_title),
            subtitle = getString(R.string.scraper_enabled_subtitle),
            checked = ScraperSiteStore.isEnabled(this),
        ) { checked ->
            ScraperSiteStore.setEnabled(this, checked)
            applyEnabledState(checked)
        }
    )

    // DoH before the site list: these sites are exactly the ones ISP resolvers blackhole, and a
    // user whose sites all fail needs to find this before they start switching them off one by
    // one looking for the working one.
    host.addView(
        scraperChoiceRow(
            title = getString(R.string.scraper_doh_title),
            subtitle = getString(R.string.scraper_doh_subtitle),
            options = DOH_PROVIDERS,
            currentValue = UserPreferences.dohProviderUrl,
        ) { value ->
            UserPreferences.dohProviderUrl = value
            // The resolver caches its built DoH client, so it has to be told rather than left to
            // notice - otherwise the change only takes effect after a restart.
            DnsResolver.setDnsUrl(value)
        }
    )

    host.addView(
        scraperToggleRow(
            title = getString(R.string.scraper_extra_buffering_title),
            subtitle = getString(R.string.scraper_extra_buffering_subtitle),
            checked = PlayerManager.isExtraBufferingEnabled(this),
        ) { checked ->
            prefs.edit().putBoolean(PlayerManager.PREF_EXTRA_BUFFERING, checked).apply()
        }
    )

    host.addView(
        TextView(this).apply {
            text = getString(R.string.scraper_sites_header)
            setTextAppearance(R.style.TextAppearance_Lumora_SettingsSubtitle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_l) }
        }
    )

    ScraperCatalog.allProviders()
        // Registry order is roughly chronological (whenever each site was added upstream), which
        // is meaningless to look at. Alphabetical is the only ordering that makes a specific site
        // findable in a list this long.
        .sortedBy { it.name.lowercase() }
        .forEach { provider ->
            val row = scraperToggleRow(
                title = provider.name,
                // The site's own language, which is the thing that actually decides whether it
                // is worth searching for a given user.
                subtitle = provider.language.uppercase(),
                checked = ScraperSiteStore.isSiteEnabled(this, provider),
            ) { checked -> ScraperSiteStore.setSiteEnabled(this, provider, checked) }
            siteRows += row
            host.addView(row)
        }

    applyEnabledState(ScraperSiteStore.isEnabled(this))
}

/**
 * DoH endpoints offered, as (label, URL). The empty URL is system DNS - see [DnsResolver], which
 * treats a blank as "do not use DoH at all".
 */
private val DOH_PROVIDERS = listOf(
    "Cloudflare" to "https://cloudflare-dns.com/dns-query",
    "Google" to "https://dns.google/dns-query",
    "Quad9" to "https://dns.quad9.net/dns-query",
    "AdGuard" to "https://dns.adguard-dns.com/dns-query",
)

/** Checkbox row in the pane's style. Mirrors [dubCheckBoxRow] but takes its state directly
 *  rather than through a pref key, since the scraper settings live in their own store. */
private fun MainActivity.scraperToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
): CheckBox = CheckBox(this).apply {
    text = twoLineSettingsText(title, subtitle)
    setTextColor(getColor(R.color.text_primary))
    setBackgroundResource(R.drawable.card_surface_background)
    val hPad = resources.getDimensionPixelSize(R.dimen.settings_gap_l)
    val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_padding_vertical)
    setPadding(hPad, vPad, hPad, vPad)
    stateListAnimator = AnimatorInflater.loadStateListAnimator(this@scraperToggleRow, R.animator.focus_scale_flat)
    isClickable = true
    isFocusable = true
    isChecked = checked
    setOnCheckedChangeListener { _, isNowChecked -> onToggle(isNowChecked) }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_m) }
}

/** Row that cycles through [options] on each press, showing the current one in its subtitle. */
private fun MainActivity.scraperChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    currentValue: String,
    onPick: (String) -> Unit,
): TextView {
    var index = options.indexOfFirst { it.second == currentValue }.takeIf { it >= 0 } ?: 0
    val row = TextView(this)

    fun render() {
        row.text = twoLineSettingsText(title, "${options[index].first} · $subtitle")
    }

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
    row.setOnClickListener {
        index = (index + 1) % options.size
        render()
        onPick(options[index].second)
    }
    render()
    return row
}
