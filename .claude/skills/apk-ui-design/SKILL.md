---
name: apk-ui-design
description: Lumora's Android Views/XML UI design system and TV D-pad focus conventions. Use whenever adding or reviewing layouts, drawables, styles, or RecyclerView adapters in this repo - new screens, settings/dialog UI, list/grid rows, focus behavior. Triggers on "layout", "drawable", "design", "UI", "focus", "D-pad", "remote", "style", "RecyclerView", "adapter", "TV UI".
---

# Lumora Android UI design system

Views/XML only. **Never** introduce Jetpack Compose - it's a hard constraint (recomposition pins the CPU on budget MediaTek TV sticks, causes dropped frames during playback). This applies to every screen, not just playback-adjacent ones.

## Reusable tokens - check these before inventing a new drawable/style

Panels/cards:
- `@drawable/bg_category_panel`, `@drawable/card_surface_background` - panel/card backgrounds
- `@drawable/bg_channel_row` - focusable list row (focused/pressed/default states, 6dp corner radius)
- `@drawable/bg_select_item` - sidebar-style selectable list item (selected/focused combinations)
- `@drawable/bg_toolbar_button` - square icon button (favorite, download, back)

Chips/pills (horizontal scrolling row of options - season pickers, version pickers, category chips):
- `@drawable/bg_season_chip` - solid-fill-on-select pill, use for anything that reads as "the current selection among several tabs" (not `bg_select_item`, which is the vertical-sidebar-list look and reads wrong stretched into a horizontal chip row)
- `@drawable/pill_number` - small static badge (meta text like "2003 · ★7 · Amazon")

Focus feedback:
- `@animator/focus_scale` - scaleX/scaleY/translationZ stateListAnimator, the standard focus "pop" on every clickable row/button/chip. Any view using it needs `clipChildren="false"` + `clipToPadding="false"` on itself AND its parent(s) up to whatever would otherwise clip the scaled-up bounds - this is the single most common layout bug in this codebase (focus ring/scale visibly cropped top/bottom).
- Text styles: `TextAppearance.Lumora.SettingsTitle` / `SettingsSubtitle`, `Widget.Lumora.SettingsNavRow` / `SettingsNavIcon` / `SettingsNavLabel` (`res/values/styles.xml`) for Settings nav rail rows specifically.

Colors: `@color/primary` / `primary_light` (brand blue), `@color/success_green` (watched/complete), `@color/warning_amber` (reminder/pending), `@color/text_primary` / `text_secondary` / `text_tertiary` (descending emphasis), `@color/surface_elevated` / `surface_emphasis` / `surface_accent` (layering).

## Recycled-view hygiene (RecyclerView adapters)

- **Reset every mutable visual property on every bind**, not just on first bind. A recycled row inherits whatever the *previous* item left on it: `scaleX`/`scaleY`/`translationX/Y/Z` (interrupted focus-scale animations leave stuck transforms), `visibility` (a field hidden conditionally for one item must be explicitly re-shown for the next), image drawables (`setImageDrawable(null)` before an async load starts, so a fast-recycled row doesn't flash the previous item's poster).
- Cancel in-flight coroutine jobs/loads on rebind (track the `Job`, cancel in a `cancelPendingLoad()`-style method called at the top of `bind()`), and guard async completions with `if (current === item)` before touching views - a slow network response landing after the row was recycled to a different item must not clobber it.
- Debounce network/image fetches by a short delay (see `LOAD_DEBOUNCE_MS` pattern in `LiveGuideAdapter`) so fast D-pad scrolling through a long list doesn't fire a request per row scrolled past.

## D-pad focus navigation - the recurring failure mode

Default Android focus search (`View.focusSearch()` / `FocusFinder`) is unreliable across three specific shapes this app has repeatedly hit:

1. **Crossing a RecyclerView boundary.** `RecyclerView.focusSearch()` scopes `FocusFinder` to itself as root, so a `nextFocusUpId`/`nextFocusDownId` pointing *outside* that RecyclerView (or outside a nested RecyclerView, e.g. a shelf row nested in an outer shelf-list RecyclerView) silently fails to resolve - the D-pad does nothing.
2. **A `wrap_content` RecyclerView inside a `ScrollView`.** The RecyclerView never scrolls itself (all children get laid out, `nestedScrollingEnabled="false"`), so default arrow-key focus search runs over the *whole enclosing screen's* geometry rather than staying scoped to the list - it can jump clean over adjacent rows to whatever unrelated view is geometrically "nearer" once the page has scrolled (e.g. UP deep in an episode list jumping to a season-chip tab instead of the row directly above).
3. **Escaping a scrollable row/list at its boundary** (first/last item) into a sibling control (tab bar, season chips, another shelf) - default search picks the nearest-by-geometry candidate, which is not necessarily the *semantically* correct one (e.g. a differently-scrolled chip row can put the "nearest" chip on the wrong tab entirely).

**The fix pattern, every time:** don't trust default focus search across any of these three boundaries. Instead:
- Read the current adapter position (`bindingAdapterPosition`), compute the intended neighbor position directly, resolve its View via `recyclerView.findViewHolderForAdapterPosition(pos ± 1)?.itemView`, and call `.requestFocus()` on it explicitly from a `View.OnKeyListener` on `KEYCODE_DPAD_UP`/`DOWN` (`ACTION_DOWN` only) - bypassing the framework's search entirely for in-list movement.
- For escaping the list at a boundary (position 0 going up, last position going down) into a specific sibling control, expose a settable target (a `var targetView: View?` on the adapter, or resolve via `v.rootView.findViewById(id)` if it's a stable id elsewhere in the activity) that the owning Activity/Fragment keeps pointed at the *currently correct* destination (e.g. the presently-selected tab chip, updated every time selection changes) - then `requestFocus()` it directly rather than falling through to default search.
- When a ViewHolder is recycled to a different position, any such stashed target/id must be reassigned on *every* bind, including a reset to `View.NO_ID`/`null` on lookup failure - never left stale from a prior bind.

Real remote input is the only reliable way to verify D-pad/focus fixes - `adb shell input keyevent` synthetic D-pad events don't always reproduce real-remote focus bugs faithfully.
