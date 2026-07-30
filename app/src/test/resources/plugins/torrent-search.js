// stream_search plugin: searches public torrent indexers (ThePirateBay, Knaben) for a title.
// Ported from the old torrentplugin APK's Scrapers.kt + TorrentSearch.kt (search half only -
// resolveNatively means Lumora's own com.lumora.torrent.TorrentEngine handles resolve(), never
// calling back into this script; JS can't run a persistent local HTTP server or hold a libtorrent
// session open, only the host app can) onto Lumora's in-process JS plugin host API - see
// JsPluginContract.kt.

PLUGIN = {
    id: "torrent.search",
    label: "Torrent Search",
    description: "Searches public torrent indexers for a title and streams the result via Lumora's built-in torrent engine.",
    capabilities: ["stream_search"],
    resolvesNatively: true,
};

var MAX_RESULTS = 200;
var MAX_SIZE_BYTES = 8 * 1024 * 1024 * 1024; // 8 GB ceiling - impractical to stream/cache above this
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
var SCRAPE_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
};

// ── Scrapers ──

/** ThePirateBay mirror. Paginated table; stops on the first empty page. */
function tpbSearch(query) {
    var base = "https://1.piratebays.to";
    var maxPages = 10;
    var out = [];
    var encoded = encodeURIComponent(query);
    for (var page = 1; page <= maxPages; page++) {
        var url = page === 1
            ? base + "/s/?q=" + encoded + "&video=on&category=0"
            : base + "/s/page/" + page + "/?q=" + encoded + "&video=on&category=0";
        var html;
        try {
            var resp = host.httpGet(url, SCRAPE_HEADERS);
            if (resp.status < 200 || resp.status >= 300) break;
            html = resp.body;
        } catch (e) {
            break;
        }

        var rows = host.selectAll(html, "table tr");
        var added = 0;
        for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            if (host.selectFirst(row, "th")) continue; // header row
            var name = host.selectText(row, "a.detLink");
            if (!name) continue;
            var magnet = host.selectAttr(row, "a[href^=magnet:]", "href");
            if (!magnet) continue;
            out.push({
                name: name,
                magnet: magnet,
                size: host.selectTextAt(row, "td", 4) || "Unknown",
                seeders: host.selectTextAt(row, "td", 5) || "Unknown",
                source: "ThePirateBay",
            });
            added++;
        }
        if (added === 0) break;
    }
    return out;
}

/** Knaben. Single path-style page, pre-sorted by seeders. */
function knabenSearch(query) {
    var base = "https://knaben.org";
    var url = base + "/search/" + encodeURIComponent(query) + "/0/1/seeders";
    var html;
    try {
        var resp = host.httpGet(url, SCRAPE_HEADERS);
        if (resp.status < 200 || resp.status >= 300) return [];
        html = resp.body;
    } catch (e) {
        return [];
    }

    var out = [];
    var rows = host.selectAll(html, "tbody tr");
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var titleCellHtml = host.selectFirst(row, "td.text-wrap");
        if (!titleCellHtml) continue;
        var magnet = host.selectAttr(titleCellHtml, "a[href^=magnet:]", "href");
        if (!magnet) continue;
        var name = host.selectAttr(titleCellHtml, "a[href^=magnet:]", "title")
            || host.selectText(titleCellHtml, "a[href^=magnet:]")
            || "Unknown";

        var allCells = host.selectAll(row, "td");
        var titleIdx = -1;
        for (var c = 0; c < allCells.length; c++) {
            if (allCells[c] === titleCellHtml) {
                titleIdx = c;
                break;
            }
        }
        var size = titleIdx >= 0 && titleIdx + 1 < allCells.length ? host.textOf(allCells[titleIdx + 1]) : "";
        var seeders = allCells.length >= 3 ? host.textOf(allCells[allCells.length - 3]) : "";

        out.push({
            name: name,
            magnet: magnet,
            size: size || "Unknown",
            seeders: seeders || "Unknown",
            source: "Knaben",
        });
    }
    return out;
}

// ── Aggregation (dedupe by infohash, keep the higher-seeders copy, sort by seeders) ──

var BTIH_REGEX = /btih:([A-Fa-f0-9]{40}|[A-Za-z2-7]{32})/;

function infohashOf(magnet) {
    var m = BTIH_REGEX.exec(magnet);
    return m ? m[1].toLowerCase() : null;
}

function seedersInt(t) {
    var n = parseInt(String(t.seeders).replace(/,/g, "").trim(), 10);
    return isNaN(n) ? 0 : n;
}

function searchAll(query) {
    var all = [].concat(knabenSearch(query), tpbSearch(query));
    var byHash = {};
    var order = [];
    for (var i = 0; i < all.length; i++) {
        var t = all[i];
        var key = infohashOf(t.magnet) || t.magnet;
        var existing = byHash[key];
        if (!existing || seedersInt(t) > seedersInt(existing)) {
            if (!existing) order.push(key);
            byHash[key] = t;
        }
    }
    var results = order.map(function (key) { return byHash[key]; });
    results.sort(function (a, b) { return seedersInt(b) - seedersInt(a); });
    return results;
}

// ── Filtering (mirrors TorrentFilter) ──

var QUALITY_TAGS = [["2160p", "4K"], ["4k", "4K"], ["uhd", "4K"], ["1080p", "1080p"], ["720p", "720p"], ["480p", "480p"]];

function qualityOf(name) {
    var lower = name.toLowerCase();
    for (var i = 0; i < QUALITY_TAGS.length; i++) {
        if (lower.indexOf(QUALITY_TAGS[i][0]) !== -1) return QUALITY_TAGS[i][1];
    }
    return null;
}

function sizeBytes(size) {
    var m = /([\d.,]+)\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB|B)/i.exec(size);
    if (!m) return null;
    var value = parseFloat(m[1].replace(/,/g, ""));
    if (isNaN(value)) return null;
    var unit = m[2].toUpperCase();
    var mult = 1;
    if (unit === "TB" || unit === "TIB") mult = 1024 * 1024 * 1024 * 1024;
    else if (unit === "GB" || unit === "GIB") mult = 1024 * 1024 * 1024;
    else if (unit === "MB" || unit === "MIB") mult = 1024 * 1024;
    else if (unit === "KB" || unit === "KIB") mult = 1024;
    return Math.floor(value * mult);
}

function withinSizeLimit(size) {
    var bytes = sizeBytes(size);
    return bytes === null || bytes <= MAX_SIZE_BYTES; // keep unparseable sizes rather than hide them
}

function normalize(title) {
    return title.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function matchesEpisode(resultName, season, episode) {
    var n = resultName.toLowerCase();
    var pad2 = function (v) { return (v < 10 ? "0" : "") + v; };
    var forms = ["s" + pad2(season) + "e" + pad2(episode), season + "x" + pad2(episode), "s" + season + "e" + episode];
    for (var i = 0; i < forms.length; i++) {
        if (n.indexOf(forms[i]) !== -1) return true;
    }
    return false;
}

function matchesMovie(resultName, wantedTitle) {
    var hay = normalize(resultName);
    var words = normalize(wantedTitle).split(" ").filter(function (w) { return w.length > 1; });
    if (words.length === 0) return true;
    return words.every(function (w) { return hay.indexOf(w) !== -1; });
}

/**
 * Strips catalog cruft that would never appear in a release name: a short provider/category tag
 * prefix like "4K-D+ - " or "D+ - ", and a bracketed year (the year is sent separately). Kept
 * conservative so real dashed titles ("Mission: Impossible - ...") survive.
 */
function cleanTitle(raw) {
    return raw
        .replace(/^[\w+-]{1,6}\s-\s/, "")
        .replace(/\(\d{4}\)/, " ")
        .replace(/\s+/g, " ")
        .trim();
}

function pad2(n) {
    return (n < 10 ? "0" : "") + n;
}

// ── stream_search entry point ──

function search(host, query, year, season, episode) {
    var title = cleanTitle((query || "").trim());
    if (!title) throw new Error("Empty search query");

    var epTag = season && episode ? "S" + pad2(season) + "E" + pad2(episode) : null;
    // For an episode search append SxxEyy (and drop the year, which rarely appears in episode
    // release names); otherwise a movie search keeps the year to disambiguate remakes.
    var searchQuery = epTag ? title + " " + epTag : year ? title + " " + year : title;

    host.reportProgress("Searching torrent sites");
    var raw = searchAll(searchQuery);
    var filtered = raw.filter(function (t) {
        return matchesMovie(t.name, title)
            && withinSizeLimit(t.size)
            && (!epTag || matchesEpisode(t.name, season, episode));
    });

    var sent = 0;
    for (var i = 0; i < filtered.length && sent < MAX_RESULTS; i++) {
        var t = filtered[i];
        host.reportResult({
            title: t.name,
            token: t.magnet, // resolvesNatively - the host resolves a magnet token itself
            seeders: seedersInt(t),
            size: t.size,
            quality: qualityOf(t.name),
            source: t.source,
        });
        sent++;
    }

    return sent === 0 ? "No torrents found" : sent + " result" + (sent === 1 ? "" : "s");
}
