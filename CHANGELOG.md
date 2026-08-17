# Changelog

## 4.3.1

### Plex
- **Signing in to Plex works again.** The QR code and 4-digit PIN appeared and then failed with a 401 about two seconds later, never leaving time to enter the code at plex.tv/link. While nobody has entered the code yet, plex.tv reports the account token as null - and that was being read as the four-character text "null" rather than as "not signed in yet", so the app decided sign-in had finished immediately and then tried to list your servers using that as the token.
- **Films and episodes that Plex hands over in a format the player cannot read now play.** Some titles failed instantly with a container error: the server was asked whether it could serve the file as-is, which it could, but nothing checked whether this player could actually read that format. Those titles are now retried as a server-side transcode instead of failing.

### Series
- **An episode watched on one provider now counts as watched on all of them.** The same show is often present on Plex, on Jellyfin and across several IPTV panels, and each copy tracked watch state under its own id - so finishing an episode left every other copy looking untouched, and their Play buttons kept offering an episode you had already seen. Watch state is now matched on the title and episode number instead, so it follows the episode rather than the copy. Finishing something also marks it on your Jellyfin and Plex servers, so their other clients agree, and watching in one of those clients marks the IPTV copies here.
- **Continue Watching tiles say which show they are.** A tile showed whatever the provider called the episode - frequently a bare "S03E03", or an episode title naming no show at all - over an episode thumbnail that most providers never send. Tiles now read as "SAS Rogue Heroes · S03E03" over the series poster.

## 4.3

### Series
- **An episode watched on one provider now counts as watched on all of them.** The same show is often present on Plex, on Jellyfin and across several IPTV panels, and each copy tracked watch state under its own id - so finishing an episode left every other copy looking untouched, and their Play buttons kept offering an episode you had already seen. Watch state is now matched on the title and episode number instead, so it follows the episode rather than the copy. Finishing something also marks it on your Jellyfin and Plex servers, so their other clients agree, and watching in one of those clients marks the IPTV copies here.
- **Continue Watching tiles say which show they are.** A tile showed whatever the provider called the episode - frequently a bare "S03E03", or an episode title naming no show at all - over an episode thumbnail that most providers never send. Tiles now read as "SAS Rogue Heroes · S03E03" over the series poster.

### Live TV
- **Channel names have more room in the guide.** The name column was narrow enough to cut off most real channel names; it now fits a name the length of "Sky Sports Football" without trimming.

### Search
- **Moving left out of the results now returns to the keyboard.** Focus went to the keyboard's container rather than to a key, so the highlight vanished and pressing OK did nothing - the keyboard looked unreachable once you had moved into the results. It now lands on the key beside the poster you left. The same fault broke moving up out of the top row of results.
- **Picking a recent search no longer leaves nothing selected.** Applying the query hid the recent-search row while it still held focus, and the results had not appeared yet, so the D-pad stopped responding entirely. Focus now lands on the first result as it arrives.

### Performance
- **EPG updates are dramatically faster on slower devices.** Parsing a guide's timestamps rebuilt a date formatter for every programme - up to 100,000 times per source - and saving it committed a separate database transaction per channel, which on the slow storage in a budget TV stick meant thousands of disk syncs. Guide scrolling also allocates far less per row.

## 4.2

### Player
- **A broken subtitle track inside a video file no longer stops the video playing.** Some episodes ended immediately with a playback error: the file's embedded SSA/ASS subtitle track had a line the subtitle parser rejected, and because subtitles were being parsed while the file was read, that one line killed the whole stream - video and audio included, and even with subtitles turned off. Subtitles are now parsed only when they are actually shown, so a bad subtitle track costs nothing but its own subtitles.
- **A film or episode now retries and switches source before showing an error.** Only live TV and Jellyfin had any recovery: one transient read error on an IPTV film ended playback with "Playback error" and left switching source as your job. VOD now gets two quiet retries of the same stream (resuming where it stopped), then an automatic switch to another copy of the same title - the film's other sources, or another provider's copy of the show matched by season and episode - before any error is shown. Retries only happen for transport failures; a missing file, or media that defeats the player's own parsers, fails the same way every time and skips straight to the next source instead of waiting out a retry.
- **Subtitles no longer switch themselves on when they are turned off.** A subtitle track tagged for the hearing impaired (SDH) was being read as a "forced" track - the narrow kind that is allowed through with subtitles off to translate a foreign-language scene - so any stream carrying English SDH played with full subtitles across the whole title.
- **Playback failures are now logged**, so an error that only happens on your device leaves something to diagnose from.

### Discover
- **Search shows results as you type, beside the keyboard.** Previously the overlay typed blind and only filled the grid behind it once submitted - and on a TV the panel shared the screen with the Discover page underneath, which pushed the lower keyboard rows and the Search button off the bottom edge, leaving the search impossible to run at all.
- Picking a title straight from the search results leaves the Discover page showing those results, so Back returns to them.

### Series
- **Episodes that have not aired yet are marked as such and can no longer be played.** A season still going out lists its remaining episodes; pressing one used to start a stream search for something that does not exist. They now show a countdown within a week ("In 3 days") or their air date, and are skipped by the Play button and auto-advance.

## 4.1

### Xtream/IPTV
- **Fixed a crash-on-launch OutOfMemoryError after adding an Xtream Codes provider.** Panels that return the live/VOD/series list as a bare JSON array at the root hit a costly org.json failure path - it fully parsed the array, then re-serialized the whole thing (the entire channel list) into a discarded error message while trying to describe the mismatch. Fixed by detecting the array up front instead of parsing-then-catching.

### Scrapers
- **AnimeSaturn scraping fixed** for the site's new layout (home, search, genres, episode lists, and playback all rebuilt around its current markup and embed protocol).
- **MKissa's crypto handshake now refreshes and retries** instead of breaking outright when the site rotates its endpoint/build config, and packed playback URLs resolve correctly again.
- **StreamWish and Upzone embeds no longer spawn popup ads** during their redirect hop.

## 4.0

### Localization
- **24 new languages added**: Arabic, Czech, Danish, German, Greek, Spanish, Finnish, Hindi, Croatian, Hungarian, Indonesian, Italian, Japanese, Korean, Dutch, Norwegian, Polish, Portuguese, Romanian, Russian, Swedish, Turkish, Ukrainian, and Chinese, on top of English and French.
- **Category and shelf labels that never translated now do.** Newest, Continue Watching, Favourites, Other, Anime and the rest of the sidebar/shelf titles were hardcoded English strings, not string resources - two of them also broke the "hide this shelf" preference once localized, since it was matching on the literal English text.

### Simple Mode
- **Live TV and Home stay reachable in Simple Mode.** It used to hide every tab, including Live TV itself, leaving only Settings/Refresh on screen. Back now falls through to Home instead of exiting the app early.

### Player
- **Audio offset is now a real, adjustable A/V sync control**, reachable from the play/pause menu. Previously stored but never actually applied to playback.
- **The Versions button only shows when there's actually another version to switch to** - live, film, and series version availability is checked upfront instead of the button always appearing and toasting "nothing else" on press.

## 3.9

### Live TV
- **Catch Up is now a dropdown on the Live TV tab** rather than a chip under the tab row. Pressing the Live TV tab you are already in opens it, and a caret on the tab shows when there is one - only when a provider actually carries archive channels. The chip cost every Live TV screen a band of height, on the pane with the least room to spare.
- **The toolbar reclaims the row the clock used to have to itself.** The clock now sits at the end of the tab row, and everything below moves up with it.

### Jellyfin
- **Your audio language setting is applied to Jellyfin titles.** A transcoded title arrives with exactly one audio track - whichever the file defaulted to - so there was nothing for the player to switch to and the setting had no effect. The language is now part of the negotiation with the server.
- **The audio track picker lists the languages a Jellyfin title actually has.** It showed a lone, unnamed "Track 1" for an episode with three languages in it, for the same reason. On a transcoded title the list now comes from the server, named by language, and picking one rebuilds the stream around that track and picks up where you were.

### Series
- **Switching to another provider's copy mid-episode keeps the episode.** Picking a different version while watching S04E01 played that provider's S01E01: the match was made on episode number alone, so every "episode 1" resolved to the first season carrying one. It now matches the season too, and says plainly when a provider doesn't carry that episode instead of playing a different one.

## 3.8

### Onboarding
- **A first-run screen replaces the old "no provider" empty state.** Pick an IPTV/Jellyfin provider, public streaming content, or both. Public streaming content downloads and enables the plugin store's stream_search/scraper_sites scripts, then drops you into Discover; either provider option opens Settings to add one.
- **A newly installed plugin switches itself on.** Updating an already-installed plugin still leaves whatever you'd chosen alone.
- **Installing or enabling a public-content plugin (torrent/site-scraper) now shows a one-time disclaimer** - these search public sites Lumora doesn't host or control. Accepted once, remembered from then on.
- **Fixed a cold start that painted nothing but the toolbar.** With no provider and an empty disk cache, the app could finish loading without ever calling the routine that shows the empty state or the catalog - Settings and Refresh were reachable, everything else was blank until the next reload.

### Discover, Series & Films
- **Series and Films stay reachable with no IPTV/Jellyfin provider**, as long as a plugin is enabled - backed by TMDB's Popular ranking instead of an empty provider catalog. English-language originals only, News-genre shows dropped, duplicate titles (regional versions of the same format, e.g. multiple "Paradise Hotel" entries) folded down to one.
- **Discover gets All/Films/Series filter chips**, pulling from the same deep Popular ranking instead of trending/week's thin ~10-per-type list.
- **The Discover tab stays visible once a provider is added**, as long as a plugin is still enabled - it used to disappear the moment any IPTV/Jellyfin provider existed, cutting off "Both" setups from their own plugins.
- Renamed the detail screen's **Find Stream** button to **Find & Play**.

### Live TV
- **Catch Up moved off the tab bar into a small chip under it**, shown only while Live TV itself is on screen. Frees a tab slot and fixes a couple of tab-bar focus/visibility edge cases that came with it.

## 3.7

### Player
- **Rewind and fast-forward can be reached with the D-pad again.** Pressing left from play/pause did nothing at all: the key was claimed to stop the side menu flying out from under the button row, and it was claimed before the row's own focus chain was ever consulted, so the move it was meant to allow could never happen. Right was unaffected, which is why only the left half of the row was stuck.
- **The remote's fast-forward and rewind keys work.** Nothing in the app claimed them, so a press went to the media session and did nothing. Both the transport keys on a media remote and the skip keys on newer TV remotes now seek, and raise the controls the same way the on-screen buttons do.
- **The controls bar stops dragging focus back to play/pause.** Every press that reset the auto-hide timer also re-focused play/pause, so walking along the row — or simply pressing rewind — bounced the selection back to the middle.

### Live TV
- **Pressing down at the bottom of the category rail no longer jumps into the channel list.** There was nothing below the last category for the remote to move to, so the selection escaped the rail and landed on whatever channel happened to sit lower on screen.
- **The same category no longer appears twice inside a genre row.** Opening News showed both "NEWS" and "News"; Cinema showed both "SKY CINEMA" and "Sky Cinema". One of each pair is the provider's own category and the other is a channel grouping the app works out from channel names, and nothing had ever compared the two — they only looked like different rows because a worked-out row is drawn in capitals. They are now one row covering every channel either of them held.
- **Categories inside a genre row are ordered by size**, largest first, so the one holding hundreds of channels is no longer sat below a category holding two.

### Films & Series
- **Expanding a grouped row stops listing the same category twice.** A row that gathers several provider categories together re-listed the spellings it had already merged — "NETFLIX ACTION" and "Netflix Action" as two children of the same row. Its categories are also ordered by size now, largest first.

### Jellyfin
- **Jellyfin no longer loads forever, with retrying the only way out.** Nothing bounded how long a request could take: the timeouts in use apply to each phase of a call, so a server trickling one byte at a time kept a request alive indefinitely, and a library is fetched a page at a time. Worse, a new attempt waits for the previous one to stop first, and a thread parked in a socket read cannot be interrupted — so every retry queued behind the stuck attempt instead of replacing it, and only succeeded once the original finally gave up on its own. Requests are now bounded end to end, and an abandoned load stops between pages instead of running to completion.
- **Jellyfin connects alongside the other providers instead of after them.** It was fetched only once every IPTV provider had finished, so a single slow provider delayed the first Jellyfin byte by minutes. It is also now bounded by the same timeout as the rest, rather than being able to hold a load open indefinitely.
- **Libraries load faster.** Live TV, movies and series are fetched at the same time rather than one after another — live being the unpredictable one, since a server with an unreachable tuner can sit there long after the other two have answered. The Continue Watching and Next Up rows are fetched together too.
- **Fixed a connection leak** on failed requests, which starved every other request into re-establishing its connection from scratch.

## 3.6

### Live TV & guide
- **The XMLTV guide now refreshes itself every 6 hours.** It never did: the periodic sync had no caller, so an added XMLTV source was downloaded exactly zero times, and the sync that was meant to run mapped programmes through a channel table nothing ever filled. Channels from M3U and Stalker providers — the ones with no other source of guide data — showed a guide that went blank a few hours in and stayed that way. Adding a source now fetches it straight away, and **Settings → EPG Sources** has a **Refresh now** button.

### Player
- **The whole player button row is reachable again on Fire TV 7.1.** Pressing right out of Sleep did nothing, and every button past it — External, Diagnostics, Record, Versions, Fit, Audio, Subtitles — could not be focused, because the row's left/right chain ran through buttons that are hidden on TV. The chain is now rebuilt over whatever is actually visible.
- **"Resume from where you left off?" no longer reappears mid-film.** Any rebuffer offered to resume the position playback had just written a moment earlier.

### Films & Series
- **Trailers, plots and artwork use the id the provider sends.** Panels send a TMDB id on almost every title and a trailer key on most of them; both were being thrown away, and the Trailer button instead guessed the title back out of the catalogue name and searched for it. Names carrying a source tag ("4K-AMZ - Elle (2026) (CA)") searched wrong and confidently matched the wrong film — "Fearless (2020)" resolved to the 2006 Jet Li film, "Deep Cover (1992)" to the 2025 one — and that wrong match fed the detail screen's plot and backdrop too. Measured over 180 random titles from a live catalogue, trailers found rose from 68 to 76 films and 59 to 72 series.
- **The category rail stops repeating itself.** A genre bucket holding a single category listed that category twice; quality tiers of one category ("Action" and "Action 4K") sat as two separate rows of the same titles and now merge into one expandable row; categories a brand had absorbed were invisible to the genre rows; and the long tail of near-empty categories folds into a single expandable "Other" row instead of taking a dozen rows for a handful of titles.
- **The season row shows for single-season shows too**, so a one-season series can be marked watched in one press instead of episode by episode.
- **"Adult Swim" is no longer treated as adult content** and sorted to the bottom of the rail.

### Elsewhere
- **The clock is back in the top-right of the toolbar**, following the device's 12/24-hour and locale settings.

### Under the hood
- **Around 1,500 lines of unreachable code removed**, including a TV Input Framework service that advertised a Lumora input the system could never tune, four Playback Settings toggles (Decoder, Buffer, Surface, FFmpeg) that reported success while writing preferences nothing read, and three database tables that were only ever written to. Existing recordings, watch history, downloads and provider settings are untouched.

## 3.5

### Films & Series
- **Episodes fill in their own details.** Providers that return nothing but "Episode 4" now get the episode title, plot and still image from TMDB, filled in a beat after the season appears. Films and shows get the same treatment for a missing plot, backdrop, release date, genre, director and cast. Only blanks are filled — a provider that sends real metadata is describing the copy actually being played, so it always wins.
- **Series categories group like films do.** A panel naming its categories "SERIES | ACTION", "SERIES | COMEDY" had every one of them collapse into a single "Series" row, which then skipped the genre buckets entirely. Categories like "APPLE+ DOCU-SERIES" also sit under their brand now instead of claiming a row of their own.
- **Near-empty categories sink to the bottom** of the Films and Series rails, so a long tail of them stops burying the ones worth browsing.
- **Marking a season watched leaves the screen where it was.** It used to rebuild the tab behind the open detail page, moving the selection somewhere arbitrary.

### Fixes
- **Fixed a crash loop on channels with no EPG data.** Caching the "no programmes" result threw, taking the app down on launch — a fresh provider with no guide data could not get past the Live tab.
- **Adding a provider now shows the catalogue.** Closing Settings before the first fetch landed left the app stuck on the "no provider" screen until it was restarted, and the tab bar stayed hidden after a successful save.
- **Discover no longer matches a title to a longer, unrelated one** — "The Last House" was claiming "The Last House on the Left" and reporting it as owned.
- **Poster and programme titles stay readable when they wrap.** Long titles shrank to unreadable at TV distance rather than ellipsizing.

## 3.4

### Android Auto
- **Lumora now appears on the car screen** and plays video there — for a parked car or a passenger display. Android Auto pulls this class of app off screen the moment the vehicle moves, as it does with every sideloaded video app, so this is playback while parked and nothing at all while driving. Every session opens on a disclaimer saying so, with the full "as is", no-warranty notice in **Settings → Playback Settings**.
- Channels come from the on-disk cache, so a car session works even if the app hasn't been opened. Only streams playable from a URL alone are listed — Stalker, plugin and Jellyfin streams need the phone app to resolve them.
- Requires **Unknown sources** in Android Auto's developer settings; this build cannot ship on Google Play.

### Settings
- **The Settings nav rail collapses**, like the category sidebar — a Collapse row at the bottom of the rail hides it, and a labelled pill brings it back at the section you were on. Portrait phones open with it hidden, so the settings panes get the full width.

## 3.3

### Playback
- **Streams the device can't decode can be handed to another player.** Hardware without a Dolby licence has no AC3/E-AC3 decoder, so those channels played with picture and no sound and nothing said why. There's now an **Open in** button in the player controls, and Lumora offers the hand-off by itself when it detects the audio can't be decoded, when playback errors out with no other version to fall back on, or when a stream keeps buffering with nowhere left to fail over to. Position, title and stream headers travel with it; downloads are handed over as a readable content URI.
- Pick a default player (VLC, MX Player, Just Player…) or be asked each time, in **Settings → Playback Settings**. The offer can be turned off there too.
- **LEFT no longer flies the side menu out** while you're moving along the player's controls bar.

### Discover
- **Titles match your library properly.** "The Odyssey" no longer resolves to "NF - Troy The Odyssey" — matching is exact against a de-decorated name, with sequel markers excluded, instead of accepting any entry that happened to contain the title.
- **Every copy is found, not just the first.** A series held on both Jellyfin and an IPTV provider showed only one of them, and the other was unreachable — including the case where Jellyfin had the season the IPTV copy was missing.
- **Tiles say where a title comes from** — Jellyfin, IPTV, or both — and the info dialog names the actual sources.
- Opening a series from Discover now carries its version chips, so you can switch source without leaving the page, and its **seasons and episodes are selectable** (the D-pad landed nowhere before).

### Browsing
- **Portrait phones hide the category rail by default**, with a wider rail and longer names when you open it — category names were cutting off to near-identical prefixes.
- **Settings and Refresh joined the tab bar** instead of sitting in a separate cluster.
- **Back out of a film or series returns you to its poster**, at the scroll position you left, rather than to the top of the tab.

## 3.2

### Category sidebar
- **Collapse categories now works on TV**, not just phones — the toggle row, the persisted collapsed state, and the re-expand pill are no longer gated to non-TV devices.
- **The re-expand pill no longer overlaps content.** Collapsing the rail now reserves the pill's own space above the active list (including above the Live TV guide's time ruler), instead of floating over the first channel/poster row.
- Reordered the sidebar utility rows so **Collapse categories sits above Show all categories**.

### Live TV
- The **preview pane is bigger on TV** — wider and a bit taller, sized to reach the top of the fifth guide row instead of a small phone-sized box floating in a lot of empty gutter space.
- **Brand mark and Live TV tab nudged slightly** on TV for better spacing.

## 3.1

### Player side menu
- **A navigation menu over the video.** Opens with LEFT on a remote, or the hamburger on phones. Columns fly out to the right: sections, then that section's categories, then its channels or titles. Picking a live channel swaps the stream in place; a film or series opens its detail page.
- **Every section expands**, not just the one playing — browse Series and Movies categories without leaving Live TV.
- **Settings is reachable from the player**, which previously meant backing out of playback to get to the gear button.

### Startup speed
- **Cold start is roughly 3.4s instead of ~30s** on a Fire TV stick with a 50k-item catalogue. Live TV appears at ~3s with the sidebar and shelves landing right behind it, where before the app kept working for another 25 seconds after the grid appeared.
- The grouping and duplicate-folding passes are now **cached to disk** along with the category rows, keyed to the catalogue and the settings that shape them — change a provider or a relevant setting and it re-derives, otherwise it restores.
- The passes themselves are faster too: name normalisation is memoised, shelf building indexes the catalogue once instead of scanning it per category row, and release years are parsed without a regex per title.

### EPG
- **The guide is stored on disk**, so relaunching no longer re-fetches every channel's programmes over the network before the grid fills in.
- A stored guide is only used while it still reaches several hours ahead — an older one is refreshed rather than half-filling the row.

### Playback
- **Subtitles off by default now covers subtitles embedded in the file**, not just sideloaded ones. VOD no longer opens with subtitles on screen when they're switched off.
- **Forced subtitles still come on** — the translated foreign-language scene in an otherwise English film is not lost to "subtitles off".
- **Audio and subtitle language pickers** in Settings > General (English by default). The audio preference applies to films and series, so a multi-audio title opens in the language you asked for; live channels keep their own audio.

### Fixes
- Anime titles and the Anime sidebar row no longer linger after the anime plugin is switched off.

## 3.0

### Providers & catalog
- **Per-provider VOD toggle.** Each provider row in Settings has a VOD checkbox (ticked = VOD enabled, default on) that hides that provider's movies and series without touching the others — including Jellyfin. Movies/series are skipped at fetch time, so gated providers don't do the slow VOD crawl.
- **Three new Filters switches** (all on by default): *Categorize live TV* and *Categorize movies & series* turn the dynamic sidebar categories off (genre buckets, brand clusters — real provider categories stay), and *Group duplicate channels* shows every HD/SD/RAW copy and multi-provider duplicate separately instead of merging them into one card with a version picker.
- **M3U providers are labelled M3U/M3U8** across the settings UI and pairing flow.
- **Settings > Plugins opens the Plugins pane** instead of routing to General.

### Sidebar
- **Pin stars reveal on focus.** A category's star is hidden until the remote lands on its row, then appears so it can be pinned — no more star clutter on every row. Newest, Jellyfin and the "Show all categories" toggle never show one (pinning them is inert), in the sidebar and on poster shelves alike.
- Category chip padding tightened so more categories fit the rail.

### Loading speed
- **Films and Series load noticeably faster**: regex passes in title normalization are now gated on the characters they need, duplicate titles are memoized instead of re-normalized, bucket resolution indexes channels once instead of rescanning per bucket, and the Newest sort no longer re-derives dates on every comparison.
- **Loading feedback appears instantly on tab switch** (same frame as the highlight) instead of after the background work finishes, and a cold start no longer flickers Loading → blank → Loading before the first tab renders.

### Cache
- **The catalog cache stays display-unfiltered.** Providers with VOD turned off keep their previously-cached movies/series in the cache, so re-enabling VOD restores them even offline.
- **A failed refresh can't wipe the cache** — an empty fetch result falls back to the on-disk catalog instead of overwriting it.

## 2.9

### Simple mode
- **New "Simple mode"** hides the tab bar so the Live TV guide fills the screen, forces VOD off, and makes Back exit straight from the top of Live TV — for setups that only ever watch live channels.
- **General settings tab** at the top of the settings rail holds Simple mode plus a standalone **Disable VOD content** switch; the VOD checkbox mirrors simple mode and re-enables when it's turned off.
- **Disable VOD skips the work, not just the UI** — providers stop fetching movies and series entirely (Xtream, Stalker, Jellyfin, M3U filter) and cached cold starts load live-only.

### Live TV & guide
- **Star icons on sidebar categories and guide rows** (white = pinned/favourited, grey = not), moved left of the name with their own focus box and key handling so a remote can actually reach them. Child-row indent tightened.
- **EPG timezone fix** — panels that store local wall-clock time as a UTC epoch are now auto-detected and shifted, so the guide lines up with the device clock instead of sitting hours off.

### Posters
- **Titles are outlined instead of shadowed.** The old blur shadow washed out against bright or busy artwork and left white text unreadable; titles now draw a hard black stroke under the text, with a light shadow kept for depth.
- **Titles cleaned of source and quality tags** (4K-AMZ, D+, PRIME:, region tags, mid-title chains), wrapping to 4 lines with auto-shrink and no mid-word breaks.
- **20% smaller posters**, plus a large-screen (4K) bucket that fits more of them on screen.

### Fixes
- **Fixed a crash opening a series from Continue Watching** (version-chip layout cast).

## 2.8

### Continue Watching
- **The ✕ on a Continue Watching shelf now clears it everywhere** - Home, Series and Movies at once, not just the tab the button was pressed on. Local resume data is wiped and Jellyfin's server-side resume list is cleared too, and a stale "hidden shelf" flag can't keep the row suppressed afterwards.
- **A Continue Watching tile opens the series page, not a replay of the episode.** Clicking an in-progress episode lands on its series' detail screen with the full episode list; the Play button targets the next-unwatched episode. Jellyfin episodes now carry their parent series id so they resolve even from the server's resume list.

### Back navigation
- **Back no longer gets stuck at the top of a category.** The old walk-up selected the sidebar's first row - which on Live TV is the classic-layout control, not a category - so every press flipped the layout and Back never reached Home. Back now scrolls to the top of the section, then goes Home, then exits the app, from any tab.

### Player
- **Jellyfin direct-play retries with a fresh URL on a stale-stream error** instead of dropping to "Playback error" - transient server timeouts and expired direct-play URLs recover on their own.
- **60s read timeout for slow remote Jellyfin/transcode servers** so a cold server start doesn't read as a failure.
- **Subtitles are opt-in.** Sidecar subtitle tracks no longer auto-select; a "Subtitles" toggle in the playback filters pane turns them on for all playback, while the existing "dubbed episodes" option stays.

### Live TV
- **Guide rows show a now/next line** under the channel name, filled from the already-fetched EPG; the focused row adds the next program ("Now: X · Next: Y").
- **Live preview black-frame detection is smarter** - it never samples mid-buffer (slow stalls can't falsely kill a healthy version) and resets its streak on each load or version switch.

### Misc
- **Touchscreen declared not required**, so TV sticks aren't filtered out of the supported device set.
- Baseline profile module removed.

## 2.7

### Torrent playback on old devices
- **Fixed crashes on older Fire TV sticks (Android 7.1).** The torrent engine's native library (libtorrent4j 2.1.0-35) crashed on API 25 devices; the 2.0.x line is built with an older toolchain and survives there. Native libs are also extracted instead of loaded straight from the APK, avoiding a page-alignment load failure on old Android.
- **Starting a torrent stream no longer crashes on Android 7.1** - the foreground-service notification uses the compat builder the platform's two-arg `Notification.Builder` doesn't exist below Android O.
- **Torrent metadata is fetched by waiting for peers properly** instead of the DHT-injection loop that starved it.

### Player
- **All track buttons are reachable with the remote again** - the D-pad focus chain was broken between Diagnostics and Record, which made the Versions, Record, Aspect Ratio, Audio and Subtitle buttons impossible to focus on a TV. The chain now runs the whole row.

### Plugins
- **Installing a plugin from a store no longer switches it on automatically.** Install puts the script on the device; enabling stays a separate, visible action on the plugin's own page (same rule as add-from-URL) - so an Install tap can't silently make a stream_search plugin start answering Find Stream and pulling its catalogue into Series.


### Design
- **Complete visual redesign.** Obsidian dark palette, Inter typeface, glass-style surfaces with hairline borders, and a refined indigo/blue accent. Everything from the toolbar to the settings panels was rebuilt to the same modern look.
- **Real icons everywhere.** Emoji tab labels, rainbow letter-chips and stock system icons are gone, replaced with a monochrome vector icon set - including new tab icons, a broadcast-cone Live TV mark, a clapperboard Movies mark, and a brand icon replacing the toolbar wordmark.
- **Films is now Movies**, with a matching clapperboard icon.
- **See All buttons tightened** so the label fits the pill instead of floating in padding.
- **About section expanded** with App/Platform rows and a Discord link.

### Playback
- **Touch gestures on the phone player.** Single tap pauses, double-tap the right half fast-forwards 10s, double-tap the left half rewinds 10s, and pinch zooms the picture 1-3x (zoomed state resets on the next video).
- **Fixed a launch crash on TV** introduced by the gesture work - the gesture detectors are now built after the activity is attached, so the app no longer dies on start.

### Speed
- **Catalog data is cached for 24 hours** and served instantly on launch - plugin discovery and network reconnects no longer gate the fast path, and nothing reconnects to providers in the background between sessions.
- **Category grouping is faster** (a quadratic scan replaced with a hash lookup) so large catalogs rebuild shelves quicker.

### Categories
- **Poster shelves and the sidebar category list now share one pipeline.** Series and Movies shelves show exactly the same categories in exactly the same order as the sidebar (Newest and Favourites stay pinned on top), so the two views can never drift apart again.
- **"Prefer dubbed audio" rows match the other settings rows**, with proper card styling, padding and D-pad focus.
- **Newly installed plugins are enabled by default**, instead of silently sitting off until you find the toggle.
- **Providers added from a discovery plugin appear in Settings instantly**, and the app jumps to the Providers pane instead of leaving you on a stale "Added" button.


### Navigation
- **Live TV is now the screen the app opens on**, and the tabs run Live TV / Series / Films / Home / Discover.
- **Back no longer drops you out of the app.** Inside a section it goes to the top of that section first (a film or series category goes back to that tab's shelves), then to Home, and only a Back pressed on Home exits.

### Favourites
- **Favourited live channels now show on Home**, in their own row. They were saved correctly but only ever appeared inside Live TV's Favourites category.
- **Favourited films show up too** - Home's Favourites row only ever looked at series, so a favourited film was saved and then never shown anywhere.
- **Hold to favourite any poster**, on Home, Series, Films and search results - the same hold that already favourites a channel in the live guide. Pinning a category from the sidebar now confirms on screen.

### Fixes
- **Posters stopped loading after browsing around.** Switching a tab between its shelves and a category grid permanently killed that list's image loading, so everything but already-cached posters stayed blank. Poster loading is also more robust in general: evicted images can no longer be recycled out from under a visible tile, and slow or unusual images no longer fail silently.
- **A second provider couldn't be added on a remote.** Opening the add form hid the button that had focus, leaving nothing focused at all - the provider type cards (Xtream, M3U, Stalker, Jellyfin) simply couldn't be reached with the D-pad.
- **"See All" is bigger**, and no longer the smallest thing on the screen to aim at.
- **Phone: the back gesture works like Back on a remote** instead of closing the app - it unwinds the same way (top of section, then Home, then exit).
- **Torrent playback no longer crashes the release build.** The torrent engine's classes were being stripped from release APKs, so playing one crashed instantly; debug builds were unaffected.

## 2.4

### Fixes
- **Settings could freeze on a routine remove/toggle.** Providers were re-fetched one after another on any add/remove/toggle; a single dead or slow provider held up every provider after it for up to its 6-minute timeout, reading as the app hanging. They're now fetched concurrently.
- **A large Stalker portal could crash the app.** A portal with tens of thousands of live channels plus a large VOD/series library held all three fully in memory before showing anything, spiking heap enough to get the process killed outright by the OS. Live channels now load and show first, before VOD/series.
- **No loading indicator on launch.** The screen sat blank while plugins were discovered and the catalog loaded, then jumped straight to content with no loading state ever having shown - looked like the app was hanging. "Loading..." with its spinner now shows immediately.
- **Removed auto-reopening the last-played channel on launch.** It's more disruptive than convenient, especially on a blank cold start.
- **Plugin pages could leave stale UI behind.** Opening a plugin's page from the Settings nav rail dropdown could leave a previous section (e.g. EPG Sources) visible underneath it, and D-pad focus could get stranded with nothing selected after adding a provider from a plugin's results.

## 2.3

### Jellyfin
- **Whole library now loads.** Library fetches were capped at 500 items per type, silently cutting off anything past that. They're paged now, so the full catalogue comes through.
- **Progress syncs both ways.** What you watch in Lumora is reported to the server, and resume points and watched marks made in any other Jellyfin client show up here. A local resume point is never rewound by an older one from the server.
- **No more black screens or silent audio.** Playback is negotiated with the server against what the device can actually decode, so files in formats a cheap stick can't handle (10-bit HEVC, TrueHD/DTS audio) are converted on the fly instead of failing. Files it can handle still play untouched.
- **Continue Watching and Next Up** rows on Home, driven by the server and deduped against local progress.
- **Favourites sync** both ways with the server.
- **Subtitles** — external and server-extracted tracks now appear in the track picker, with forced/default flags honoured.
- **Chapter picker** and **seek-preview thumbnails** (trickplay) in the player.
- Real season names including **Specials**, and per-episode watched state pulled from the server.

### Library browsing
- Films and Series get a **Jellyfin shelf** directly under "Newest", while its titles stay merged into the normal genre and provider shelves.
- **Better duplicate matching** — a title your IPTV provider lists with a region or quality tag ("(US)", "(FHD)") now groups with the same title from another source instead of showing twice.

### Fixes
- **Phone: toolbar was under the status bar.** On a portrait phone the settings and refresh buttons sat behind the clock and signal icons and couldn't be tapped at all (landscape only worked because the status bar is shorter there). The app's chrome is now inset out from the system bars.
- **Settings screen was showing stray UI.** Home's search bar and the whole Discover pane stayed on screen above Settings; both are now hidden while it's open.

## 2.2.1

- Category visibility, channel resume, and adult-history exclusion fixes.

## 2.2

- Live preview, version ranking, Jellyfin episodes, and provider toggling fixes.
