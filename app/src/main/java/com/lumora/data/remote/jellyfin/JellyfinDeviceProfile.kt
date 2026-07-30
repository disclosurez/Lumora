package com.lumora.data.remote.jellyfin

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject

/**
 * The DeviceProfile sent to Jellyfin's /Items/{id}/PlaybackInfo so the *server* decides
 * DirectPlay vs DirectStream vs transcode, instead of the app blindly requesting
 * `?static=true` and hoping ExoPlayer can decode whatever comes back.
 *
 * Probed off this device's real decoders via MediaCodecList rather than assumed: the whole
 * point on a budget TV stick is that HEVC 10-bit, AV1, or a TrueHD/DTS track may simply have
 * no decoder, and the only way the server can know to transcode is if we tell it what we
 * can actually play. A hardcoded "everything" profile is what makes a title open to a black
 * screen or silent audio instead.
 *
 * Results are cached - MediaCodecList enumeration is not free and this is consulted on every
 * play, on devices where it is slowest.
 */
object JellyfinDeviceProfile {

    /** ~20 Mbit default ceiling for transcodes; direct play is unaffected by this. */
    const val DEFAULT_MAX_BITRATE = 20_000_000

    private const val MIME_H264 = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_VP9 = "video/x-vnd.on2.vp9"
    private const val MIME_AV1 = "video/av01"
    private const val MIME_MPEG2 = "video/mpeg2"
    private const val MIME_MPEG4 = "video/mp4v-es"
    private const val MIME_VC1 = "video/wvc1"

    private const val MIME_AAC = "audio/mp4a-latm"
    private const val MIME_MP3 = "audio/mpeg"
    private const val MIME_AC3 = "audio/ac3"
    private const val MIME_EAC3 = "audio/eac3"
    private const val MIME_DTS = "audio/vnd.dts"
    private const val MIME_TRUEHD = "audio/true-hd"
    private const val MIME_FLAC = "audio/flac"
    private const val MIME_OPUS = "audio/opus"
    private const val MIME_VORBIS = "audio/vorbis"

    private val decoderCache = HashMap<String, Boolean>()
    private var hevc10BitCache: Boolean? = null

    /** True when this device has *any* decoder for [mime]. Encoders are ignored - a stick
     *  that can encode H.264 tells us nothing about what it can play back. */
    @Synchronized
    fun hasDecoder(mime: String): Boolean = decoderCache.getOrPut(mime) {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /** HEVC Main10 specifically. Plenty of cheap sticks expose an HEVC decoder that is
     *  8-bit only, and a 10-bit HDR file handed to it plays as a green/garbled mess rather
     *  than failing outright - so mime support alone is the wrong question to ask. */
    @Synchronized
    fun supportsHevc10Bit(): Boolean {
        hevc10BitCache?.let { return it }
        val supported = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) } &&
                    // Main10 and Main10HDR10 only - HDR10Plus is API 29 and a decoder that
                    // reports it always reports one of these two as well, so there's nothing
                    // to gain from a constant this project's minSdk can't see.
                    info.getCapabilitiesForType(MIME_HEVC).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    }
            }
        }.getOrDefault(false)
        hevc10BitCache = supported
        return supported
    }

    /** Jellyfin codec names (not Android mimes) this device can decode, video then audio. */
    private fun videoCodecs(): List<String> = buildList {
        if (hasDecoder(MIME_H264)) add("h264")
        if (hasDecoder(MIME_HEVC)) { add("hevc"); add("h265") }
        if (hasDecoder(MIME_VP9)) add("vp9")
        if (hasDecoder(MIME_AV1)) add("av1")
        if (hasDecoder(MIME_MPEG2)) add("mpeg2video")
        if (hasDecoder(MIME_MPEG4)) add("mpeg4")
        if (hasDecoder(MIME_VC1)) add("vc1")
    }

    private fun audioCodecs(): List<String> = buildList {
        if (hasDecoder(MIME_AAC)) { add("aac"); add("aac_latm") }
        if (hasDecoder(MIME_MP3)) { add("mp3"); add("mp2") }
        if (hasDecoder(MIME_AC3)) add("ac3")
        if (hasDecoder(MIME_EAC3)) add("eac3")
        if (hasDecoder(MIME_DTS)) { add("dca"); add("dts") }
        if (hasDecoder(MIME_TRUEHD)) add("truehd")
        if (hasDecoder(MIME_FLAC)) add("flac")
        if (hasDecoder(MIME_OPUS)) add("opus")
        if (hasDecoder(MIME_VORBIS)) add("vorbis")
        add("pcm_s16le")
    }

    /**
     * Containers Media3 can demux directly. Deliberately excludes avi/wmv/flv - the server
     * remuxes those into a supported container (DirectStream) rather than us failing on them.
     */
    private const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mkv,webm,mov,ts,mpegts,mp3,flac,ogg,m4a,aac,wav"

    /**
     * Text subtitle formats Media3 renders itself, so they can be delivered as sidecar
     * files ("External") and switched instantly with no server work. Image-based formats
     * (PGS/VOBSUB) have no Media3 renderer at all, so they're declared "Encode" - the server
     * burns them into the video when the user actually selects one, which is the only way
     * they can ever be shown.
     */
    private fun subtitleProfiles(): JSONArray = JSONArray().apply {
        for (format in listOf("vtt", "webvtt", "srt", "subrip", "ass", "ssa", "ttml")) {
            put(JSONObject().put("Format", format).put("Method", "External"))
        }
        for (format in listOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub")) {
            put(JSONObject().put("Format", format).put("Method", "Encode"))
        }
    }

    /**
     * Constraints that hold even for codecs we *do* have a decoder for. Currently just the
     * 10-bit HEVC case: the codec is listed as direct-playable, but a device whose HEVC
     * decoder is 8-bit only must have anything deeper transcoded rather than handed over.
     */
    private fun codecProfiles(): JSONArray = JSONArray().apply {
        if (hasDecoder(MIME_HEVC) && !supportsHevc10Bit()) {
            put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Codec", "hevc")
                    .put(
                        "Conditions",
                        JSONArray().put(
                            JSONObject()
                                .put("Condition", "LessThanEqual")
                                .put("Property", "VideoBitDepth")
                                .put("Value", "8")
                                .put("IsRequired", true)
                        )
                    )
            )
        }
    }

    /**
     * The full profile. [maxBitrate] caps transcodes (and is what the server uses to pick a
     * transcode ladder rung); direct play ignores it.
     */
    fun build(maxBitrate: Int = DEFAULT_MAX_BITRATE): JSONObject {
        val video = videoCodecs().joinToString(",")
        val audio = audioCodecs().joinToString(",")

        val directPlay = JSONArray().apply {
            if (video.isNotEmpty()) {
                put(
                    JSONObject()
                        .put("Container", DIRECT_PLAY_CONTAINERS)
                        .put("Type", "Video")
                        .put("VideoCodec", video)
                        .put("AudioCodec", audio)
                )
            }
            put(JSONObject().put("Container", "mp3,aac,flac,ogg,m4a,wav").put("Type", "Audio"))
        }

        // HLS/TS with H.264 + AAC is the one combination every device here can play, so it's
        // the fallback the server transcodes into. BreakOnNonKeyFrames keeps seeking usable
        // on a live transcode instead of stalling at segment boundaries.
        val transcoding = JSONArray().apply {
            put(
                JSONObject()
                    .put("Container", "ts")
                    .put("Type", "Video")
                    .put("VideoCodec", "h264")
                    .put("AudioCodec", "aac,mp3")
                    .put("Protocol", "hls")
                    .put("Context", "Streaming")
                    .put("MaxAudioChannels", "6")
                    .put("MinSegments", 1)
                    .put("BreakOnNonKeyFrames", true)
            )
            put(
                JSONObject()
                    .put("Container", "mp3")
                    .put("Type", "Audio")
                    .put("AudioCodec", "mp3")
                    .put("Protocol", "http")
                    .put("Context", "Streaming")
            )
        }

        return JSONObject()
            .put("MaxStreamingBitrate", maxBitrate)
            .put("MaxStaticBitrate", maxBitrate)
            .put("MusicStreamingTranscodingBitrate", 384_000)
            .put("DirectPlayProfiles", directPlay)
            .put("TranscodingProfiles", transcoding)
            .put("ContainerProfiles", JSONArray())
            .put("CodecProfiles", codecProfiles())
            .put("SubtitleProfiles", subtitleProfiles())
    }
}
