# Changelog

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
