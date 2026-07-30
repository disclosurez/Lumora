package com.lumora.plugin.js

/**
 * A community plugin store: a URL to a small JSON catalog (see [PluginStoreManager.fetchCatalog]
 * for the schema) listing scripts a user can install with one tap, instead of needing the exact
 * `.js` URL of each one. Anyone can host one - it's just a static JSON file plus the scripts it
 * points at, e.g. served from a GitHub repo via raw.githubusercontent.com.
 */
data class PluginStore(
    val url: String,
    /** Self-declared, falls back to the URL if the catalog has none / hasn't been fetched yet. */
    val name: String?,
    /** The default store (Lumora's own plugin repo, connected automatically) can't be removed. */
    val removable: Boolean,
)

/** One script listed in a store's catalog, not yet necessarily installed. */
data class StoreScript(
    val id: String,
    val label: String,
    val description: String?,
    val capabilities: Set<String>,
    /** Resolved to an absolute URL relative to the catalog's own location. */
    val fileUrl: String,
)
