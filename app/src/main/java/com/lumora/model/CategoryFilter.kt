package com.lumora.model

/**
 * A row in the category sidebar. Three shapes:
 *  - "All" row: id == null.
 *  - Leaf row: id is the raw provider category id, matchIds == {id}.
 *  - Parent (merged) row: id is a synthetic "group:<label>" key, isParent == true,
 *    matchIds is the union of every child's raw category id, and its children
 *    are flattened into the adjacent rows (isChild == true) when expanded.
 *  - Brand row: id is a synthetic "brand:<label>" key, channelIds is the specific
 *    set of channel ids clustered under that name (spans whatever real provider
 *    categories those channels actually live in, so matchIds doesn't apply).
 */
data class CategoryFilter(
    val id: String?,
    val name: String,
    val count: Int,
    val pinned: Boolean = false,
    val matchIds: Set<String> = emptySet(),
    val isParent: Boolean = false,
    val isChild: Boolean = false,
    val expanded: Boolean = false,
    val channelIds: Set<String> = emptySet(),
    /** A row the app synthesised rather than one the provider named: Live TV's dynamic
     *  buckets and the brand rows on every tab. Rendered uppercase to separate them from
     *  the provider's own categories listed below them. */
    val isDynamic: Boolean = false
)
