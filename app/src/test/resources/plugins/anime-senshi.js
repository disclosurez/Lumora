// Anime stream_search plugin: AniList search + senshi.live streaming.
// Ported from the old animeplugin APK (AniListClient.kt + SenshiProvider.kt/SenshiParser.kt +
// StreamService.kt) onto Lumora's in-process JS plugin host API - see JsPluginContract.kt.
// Search results and resolve returns carry audio ("sub"/"dub") for the host's audioOf.

PLUGIN = {
    id: "anime.senshi",
    label: "Anime (Senshi)",
    description: "AniList search + senshi.live streaming for anime.",
    capabilities: ["stream_search"],
    contentTypes: ["anime"],
};

var SENSHI_BASE = "https://senshi.live";
var SENSHI_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36";
var MAX_RESULTS = 50;

var SEARCH_QUERY = "query ($search: String) { Page(page: 1, perPage: 10) { media(search: $search, type: ANIME) { id idMal title { romaji english native } episodes status format seasonYear coverImage { large } studios(isMain: true) { nodes { name } } } } }";

// ── AniList ──

function aniListSearch(query) {
    var body = JSON.stringify({ query: SEARCH_QUERY, variables: { search: query } });
    var resp = host.httpPost("https://graphql.anilist.co", body, {
        "Content-Type": "application/json",
        "Accept": "application/json",
    });
    if (resp.status < 200 || resp.status >= 300) {
        throw new Error("AniList API error " + resp.status);
    }
    var root = JSON.parse(resp.body);
    var media = root && root.data && root.data.Page && root.data.Page.media;
    if (!media) return [];

    var results = [];
    for (var i = 0; i < media.length && i < 8; i++) {
        var m = media[i];
        if (!m || !m.title) continue;
        var studios = m.studios && m.studios.nodes;
        var studioName = studios && studios.length > 0 ? studios[0].name : null;
        results.push({
            id: m.id,
            malId: m.idMal !== undefined && m.idMal !== null ? m.idMal : null,
            titleRomaji: m.title.romaji || "",
            titleEnglish: m.title.english && m.title.english.trim() ? m.title.english : null,
            episodes: m.episodes !== undefined && m.episodes !== null ? m.episodes : null,
            format: m.format && m.format.trim() ? m.format : null,
            seasonYear: m.seasonYear !== undefined && m.seasonYear !== null ? m.seasonYear : null,
            studio: studioName,
        });
    }
    return results;
}

function displayTitle(match) {
    if (match.titleEnglish) return match.titleEnglish;
    if (match.titleRomaji) return match.titleRomaji;
    return "Unknown";
}

// ── Senshi ──

function senshiGet(url) {
    var resp = host.httpGet(url, {
        "User-Agent": SENSHI_UA,
        "Referer": SENSHI_BASE + "/",
        "Accept": "application/json, */*",
    });
    if (resp.status < 200 || resp.status >= 300) {
        throw new Error("Senshi HTTP " + resp.status + " for " + url);
    }
    return resp.body;
}

function fetchCatalogEpisodeNumbers(malId) {
    var text;
    try {
        text = senshiGet(SENSHI_BASE + "/episodes/" + malId);
    } catch (e) {
        return [];
    }
    var arr;
    try {
        arr = JSON.parse(text);
    } catch (e) {
        return [];
    }
    if (!Array.isArray(arr)) return [];
    var numbers = [];
    for (var i = 0; i < arr.length; i++) {
        var n = arr[i] && arr[i].ep_id;
        if (n && n > 0) numbers.push(n);
    }
    return numbers;
}

function fetchEmbeds(malId, episode) {
    var text;
    try {
        text = senshiGet(SENSHI_BASE + "/episode-embeds/" + malId + "/" + episode);
    } catch (e) {
        return [];
    }
    var arr;
    try {
        arr = JSON.parse(text);
    } catch (e) {
        return [];
    }
    return Array.isArray(arr) ? arr : [];
}

function isDub(source) {
    return (source.status || "").toLowerCase() === "dub";
}

// Senshi labels these sources "HardSub", but the video it serves is clean - the subtitles are
// published as separate WebVTT files listed in a sidecar JSON. Nothing in the HLS playlist
// advertises them (single media playlist, no #EXT-X-MEDIA SUBTITLES rendition), so unless they
// are handed to the host explicitly the episode plays with no subtitles at all.
//
// Two ways to find that JSON, in order: the `sub.info` query parameter on the serverFM embed
// URL (authoritative - it's what the source's own web player reads), then the conventional
// filename next to the stream, for embeds that carry no serverFM.
function subInfoUrl(source) {
    var fm = source.serverFM || "";
    var marker = "sub.info=";
    var at = fm.indexOf(marker);
    if (at !== -1) {
        var raw = fm.substring(at + marker.length).split("&")[0];
        if (raw) {
            try {
                return decodeURIComponent(raw);
            } catch (e) {
                return raw;
            }
        }
    }
    var base = source.masked_base_url || "";
    return base ? base + "/sub_filemoon.json" : null;
}

function fetchSubtitles(source) {
    var url = subInfoUrl(source);
    if (!url) return [];
    var resp = host.httpGet(url, {
        "User-Agent": SENSHI_UA,
        "Referer": SENSHI_BASE + "/",
        "Accept": "application/json, */*",
    });
    if (resp.status < 200 || resp.status >= 300) return [];
    var arr;
    try {
        arr = JSON.parse(resp.body);
    } catch (e) {
        return [];
    }
    if (!Array.isArray(arr)) return [];

    var out = [];
    for (var i = 0; i < arr.length; i++) {
        var t = arr[i];
        var src = t && t.src ? String(t.src) : "";
        if (src.indexOf("http://") !== 0 && src.indexOf("https://") !== 0) continue;
        var label = t.label ? String(t.label) : null;
        // The label is all the source gives ("CR (ENG)", "English", ...) - enough to pick
        // English out of it for the track picker, and null rather than a wrong guess otherwise.
        var language = label && /eng/i.test(label) ? "en" : null;
        out.push({ url: src, label: label, language: language, default: !!t["default"] });
    }
    return out;
}

// Dub coverage isn't in the catalog list; probe the first episode's embeds, same as the
// original Kotlin SenshiProvider.checkAvailability.
function checkAvailability(malId) {
    var episodes = fetchCatalogEpisodeNumbers(malId);
    if (episodes.length === 0) return { sub: [], dub: [] };
    var firstEp = Math.min.apply(null, episodes);
    var hasDub = false;
    try {
        hasDub = fetchEmbeds(malId, firstEp).some(isDub);
    } catch (e) {
        hasDub = false;
    }
    return { sub: episodes, dub: hasDub ? episodes : [] };
}

function pad2(n) {
    return (n < 10 ? "0" : "") + n;
}

// ── stream_search entry points ──

function search(host, query, year, season, episode) {
    query = (query || "").trim();
    if (!query) throw new Error("Empty search query");
    var ep = episode && episode > 0 ? episode : 1;

    host.reportProgress('Searching AniList for "' + query + '"');
    var matches = aniListSearch(query);
    if (matches.length === 0) {
        host.reportProgress("No anime found on AniList");
        return "No results";
    }
    host.reportProgress("Found " + matches.length + " anime, checking provider availability");

    var epTag = season && episode ? " S" + pad2(season) + "E" + pad2(episode) : "";
    var sent = 0;

    for (var i = 0; i < matches.length && sent < MAX_RESULTS; i++) {
        var match = matches[i];
        var malId = match.malId;
        if (!malId || malId <= 0) continue;

        var title = displayTitle(match);
        var yearTag = match.seasonYear ? " (" + match.seasonYear + ")" : "";
        var infoParts = [];
        if (match.format && match.format !== "TV") infoParts.push(match.format);
        if (match.episodes) infoParts.push(match.episodes + "ep");
        var info = infoParts.join(" ");

        var available = null;
        try {
            available = checkAvailability(malId);
        } catch (e) {
            available = null;
        }

        var episodeToCheck = Math.min(ep, match.episodes || ep);

        if (available && available.sub.indexOf(episodeToCheck) !== -1) {
            host.reportResult({
                title: title + epTag + " [Sub]" + yearTag,
                token: "senshi:" + malId + ":sub",
                quality: "Sub",
                audio: "sub",
                source: "Senshi",
                size: info,
            });
            sent++;
        }
        if (available && available.dub.indexOf(episodeToCheck) !== -1) {
            host.reportResult({
                title: title + epTag + " [Dub]" + yearTag,
                token: "senshi:" + malId + ":dub",
                quality: "Dub",
                audio: "dub",
                source: "Senshi",
                size: info,
            });
            sent++;
        }
        // Fallback: if Senshi returned nothing, still show the match with 'any' audio.
        if (!available) {
            host.reportResult({
                title: title + yearTag,
                token: "senshi:" + malId + ":sub",
                source: "Senshi",
                size: info,
            });
            sent++;
        }
    }

    return sent === 0 ? "No playable anime found" : sent + " result" + (sent === 1 ? "" : "s");
}

function resolve(host, token, season, episode) {
    var ep = episode && episode > 0 ? episode : 1;
    var parts = (token || "").split(":");
    if (parts.length < 3 || parts[0] !== "senshi") {
        throw new Error("Unknown provider in token");
    }
    var malId = parseInt(parts[1], 10);
    if (!malId) throw new Error("Invalid token format");
    var audio = parts[2];

    host.reportProgress("Resolving episode " + ep + " " + audio + " via Senshi");

    var embeds = fetchEmbeds(malId, ep);
    var match = null;
    for (var i = 0; i < embeds.length; i++) {
        if ((audio === "dub") === isDub(embeds[i])) {
            match = embeds[i];
            break;
        }
    }
    if (!match) throw new Error("Senshi episode " + ep + " has no " + audio + " source");

    var hlsUrl = match.url && match.url.trim() ? match.url : null;
    var fallbackUrl = match.server2 && match.server2.trim()
        ? match.server2
        : (match.serverFM && match.serverFM.trim() ? match.serverFM : null);
    var streamUrl = hlsUrl || fallbackUrl;
    if (!streamUrl) throw new Error("Senshi episode " + ep + " has no playable sources");

    var subtitles = fetchSubtitles(match);
    if (subtitles.length > 0) {
        host.reportProgress("Stream ready (" + subtitles.length + " subtitle track(s))");
    } else {
        host.reportProgress("Stream ready");
    }
    // The CDN (ninstream etc.) hotlink-protects its playlist behind a Referer of the embed host -
    // without it the player's fetch 403s. Return headers alongside the URL so the host applies
    // them (verified: Referer https://senshi.live/ flips the playlist 403 -> 200).
    // Headers and subtitles as JSON strings, not nested objects/arrays: the host reads the
    // resolve return via a shallow toMap that can drop a nested value, so a string round-trips
    // reliably.
    return {
        url: streamUrl,
        headers: JSON.stringify({
            "Referer": SENSHI_BASE + "/",
            "Origin": SENSHI_BASE,
            "User-Agent": SENSHI_UA,
        }),
        subtitles: JSON.stringify(subtitles),
        audio: audio,
    };
}
