# Lumora

**Lumora** is a fast, lightweight IPTV client for Android, Android TV, and Fire TV. It speaks **Xtream Codes, M3U/M3U8 playlists, Stalker Portal, and Jellyfin** — any number of them running at the same time — and merges Live TV, Movies, and Series from all of them into one clean, D-pad-friendly interface.

It's a native XML/Views app with **no Jetpack Compose anywhere**, and that's deliberate: on the budget TV boxes and streaming sticks these apps actually run on, heavier UI frameworks pin the CPU and cost you frames mid-playback. Everything here is built to stay smooth on hardware that has nothing to spare.

Nothing is behind a paywall — the multi-playlist support, EPG guide, recording, and catch-up that comparable players charge for are simply included.

# DOWNLOADER CODE - 6626802

https://discord.gg/cNKYGhQWvq

> **Lumora is a player, not a provider.** It doesn't include, sell, host, or supply any channels, streams, or subscriptions of any kind. You bring your own IPTV service (Xtream Codes / M3U / Stalker Portal) or your own Jellyfin server, and Lumora simply plays it back. See [Disclaimer](#disclaimer).

## Highlights

- **Similar to TiVimate or Sparkle TV, without paying for the features.** Multiple playlists, the EPG guide, DVR recording, catch-up, favourites and multi-provider support are the things those players put behind a premium subscription or one-off unlock. In Lumora they're all just included, free.
- **Optional Jellyfin support, properly done.** Point it at your own Jellyfin server and its films and series merge into the same shelves as your IPTV catalogue (same title from both = one card). Resume points, watched marks and favourites sync both ways with the server, and files your stick can't decode are converted by the server on the fly rather than opening to a black screen.
- **Run every subscription at once.** Any number of Xtream Codes, M3U and Stalker Portal providers active together, merged into one catalogue instead of switching between playlists.
- **Live TV that tidies itself up.** Duplicate feeds of the same channel collapse into one entry at the best available quality (4K → FHD → HD → SD), with instant fallback to any other copy mid-playback; Sports, News, Music and Cinema surface at the top automatically whatever your provider filed them under.
- **A proper EPG guide.** Scrollable program grid with per-channel schedules, now/next info, program reminders, timeshift/catch-up and DVR recording.
- **Full VOD browsing.** Movies and Series with category shelves, poster grids, season/episode browsing, episode-level Continue Watching and auto-advance to the next episode.
- **Offline downloads.** Save movies and episodes to the phone and watch them with no connection at all (phone only).
- **Built for the remote and for cheap hardware.** Native XML/Views, no Jetpack Compose — it stays smooth on the low-powered sticks these apps usually stutter on, and everything is reachable with a D-pad.

## Screenshots

| Live TV guide | Series library |
|---|---|
| ![Live TV guide with EPG grid and channel preview](docs/screenshots/live-tv.png) | ![Series library with category shelves](docs/screenshots/series.png) |

| Films library | Discover |
|---|---|
| ![Films library with Newest and category shelves](docs/screenshots/films.png) | ![Discover search with poster results](docs/screenshots/discover.png) |

| Provider setup | Multiple providers at once |
|---|---|
| ![Settings screen showing QR phone-pairing flow](docs/screenshots/settings-qr.png) | ![Providers settings listing an Xtream provider and a Jellyfin server together, both enabled, with server addresses redacted](docs/screenshots/settings-providers.png) |

## Highlights

- **Similar to TiVimate or Sparkle TV, without paying for the features.** Multiple playlists, the EPG guide, DVR recording, catch-up, favourites and multi-provider support are the things those players put behind a premium subscription or one-off unlock. In Lumora they're all just included, free.
- **Optional Jellyfin support, properly done.** Point it at your own Jellyfin server and its films and series merge into the same shelves as your IPTV catalogue (same title from both = one card). Resume points, watched marks and favourites sync both ways with the server, and files your stick can't decode are converted by the server on the fly rather than opening to a black screen.
- **Run every subscription at once.** Any number of Xtream Codes, M3U and Stalker Portal providers active together, merged into one catalogue instead of switching between playlists.
- **Live TV that tidies itself up.** Duplicate feeds of the same channel collapse into one entry at the best available quality (4K → FHD → HD → SD), with instant fallback to any other copy mid-playback; Sports, News, Music and Cinema surface at the top automatically whatever your provider filed them under.
- **A proper EPG guide.** Scrollable program grid with per-channel schedules, now/next info, program reminders, timeshift/catch-up and DVR recording.
- **Full VOD browsing.** Movies and Series with category shelves, poster grids, season/episode browsing, episode-level Continue Watching and auto-advance to the next episode.
- **Offline downloads.** Save movies and episodes to the phone and watch them with no connection at all (phone only).
- **Built for the remote and for cheap hardware.** Native XML/Views, no Jetpack Compose — it stays smooth on the low-powered sticks these apps usually stutter on, and everything is reachable with a D-pad.

## Features

### Live TV
- **Xtream Codes, M3U/M3U8, Stalker Portal, and Jellyfin** provider support
- **Smart channel merging** — automatically collapses duplicate channel feeds (different quality tiers, source tags, or provider re-listings of the same channel) into a single entry, auto-selecting the best available quality (4K/UHD → FHD → HD → SD), with instant manual fallback to any other version mid-playback
- **Dynamic categories** — Sports, News, Music, and Cinema surface automatically at the top of the channel list, pulling in matching content regardless of which raw provider category it's filed under; everything else cascades below
- **Brand/franchise clustering** — channel families (e.g. all feeds of the same sports network) group into a single expandable category automatically
- **Live EPG guide** — scrollable program grid with per-channel schedules, "now playing" info, and program reminders
- **Picture-in-picture live preview** while browsing the channel list
- **Timeshift/catch-up** playback where the provider supports it
- **DVR recording** — schedule and manage recordings directly from the guide

### Movies & Series
- Full VOD library browsing with category shelves
- Duplicate/version merging for movies re-listed under multiple source tags
- Season/episode browser with **episode-level "Continue Watching"** — resumes the exact episode you left off on, and auto-advances to the next episode when one finishes
- Poster grid view for browsing a full category, plus a global search with poster results
- Pin, hide, and "See All" controls on every category shelf

### Jellyfin (optional)

Lumora is an IPTV player first — Jellyfin is an extra slot you can fill if you happen to run
a server, and everything below is inert if you don't.

- Its films and series merge into the **same shelves and poster grids** as your IPTV catalogue, with a dedicated "Jellyfin" shelf on Films and Series; a title both your provider and your server carry becomes one card with both sources selectable
- **Two-way progress sync** — resume points and watched marks are read from and reported back to the server, so viewing in any other Jellyfin client is reflected here (and vice versa)
- Server-driven **Continue Watching** and **Next Up** rows on Home, deduped against local progress
- **Favourites sync** both ways
- **Format handling** — plays the original file untouched where the device can handle it, and asks the server to convert it on the fly where it can't (10-bit HEVC, TrueHD/DTS audio and similar), based on what the device actually reports it can decode
- External and server-extracted **subtitle tracks** loaded with their forced/default flags honoured
- **Chapter picker** and **seek-preview thumbnails** (trickplay) in the player
- Real season names (Specials included) and per-episode watched state from the server
- Password or **Quick Connect** sign-in

### Playback
- Built on **Media3 (ExoPlayer)** with HLS, DASH, and RTSP support
- Adjustable playback speed, sleep timer, aspect ratio control, audio/subtitle track selection
- Automatic quality/source failover on stream error or sustained buffering
- Google Cast support
- Android TV **TV Input Framework** integration (live channels surface in the system TV app) and **Watch Next** row support

### Other
- QR-code pairing — configure a provider on your phone, scan to push it to the TV instantly
- Parental controls with PIN-gated adult content filtering
- Non-English content filtering
- Local backup/restore (JSON export/import) plus optional Google Drive backup
- Downloads manager for offline movie/episode playback (phone only)
- Custom EPG source support (XMLTV)

## Tested devices

Verified on real hardware, not just an emulator:

- **Amazon Fire TV Stick** — multiple generations, from the 1st gen through to the current one (the oldest sticks are exactly the low-powered hardware the Views-only UI exists for)
- **Sony Bravia** Android TV
- **Samsung** Android phone

Anything else on Android 7.1 (SDK 25) or newer should work; those are just the devices it's actually been exercised on.

## Tech Stack

- **Language:** Kotlin
- **UI:** Android Views/XML
- **Playback:** [AndroidX Media3](https://developer.android.com/media/media3) (ExoPlayer)
- **Persistence:** Room, WorkManager (background sync), SharedPreferences
- **Networking:** OkHttp
- **Min SDK:** 25 (Android 7.1) · **Target SDK:** 36

## Installation

Grab the latest signed APK from the [Releases](https://github.com/disclosurez/lumora/releases) page and sideload it. Lumora checks GitHub Releases on launch and will prompt you when a new version is available.

On first launch, you'll be asked to add a provider — this is your own Xtream Codes / M3U / Stalker Portal IPTV subscription, or your own Jellyfin server. Lumora has no content of its own and cannot supply one for you.

## Building from Source

```bash
git clone https://github.com/disclosurez/lumora.git
cd lumora
./gradlew :app:assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Release builds

Release builds are signed using a keystore referenced by `keystore.properties` in the project root (not committed — see `.gitignore`). To build a release APK yourself, create your own keystore and a `keystore.properties` file:

```properties
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run:

```bash
./gradlew :app:assembleRelease
```

## Project Structure

```
app/src/main/java/com/lumora/
├── adapter/       RecyclerView adapters (channels, categories, shelves, episodes, ...)
├── cache/         Local caches (favorites, playback position, watch history, ...)
├── data/          Providers, Room database, backup, sync workers, update checker
├── download/      Offline download manager
├── model/         Core data models (Channel, Provider, CategoryFilter, ...)
├── pairing/       QR provider-pairing flow
├── parser/        M3U / Xtream Codes parsing
├── player/        Playback stack (ExoPlayer wrapper, Cast, subtitles, media session)
├── plugin/        Provider plugin interface
├── recording/      DVR scheduling and capture
├── reminder/      Program reminder scheduling
├── tv/            Android TV Input Framework integration
└── util/          Shared helpers (content grouping/dedup, URL utils, ...)
```

## Contributing

Issues and pull requests are welcome. Please open an issue describing the change before submitting a large PR.

## Disclaimer

**Lumora provides no content, service, or subscription of its own.** It is a generic IPTV/media client, comparable to a web browser or a media player — it does not host, stream, sell, endorse, or have any affiliation with any channel, movie, series, or IPTV service. All content played through Lumora comes exclusively from a provider (Xtream Codes account, M3U playlist, Stalker Portal, or Jellyfin server) that *you* configure, and which you are solely responsible for legally obtaining access to. The developers of Lumora have no visibility into, and no control over, what any given provider serves.

## License

No license has been specified for this project yet. All rights reserved by the author unless/until a license is added.
