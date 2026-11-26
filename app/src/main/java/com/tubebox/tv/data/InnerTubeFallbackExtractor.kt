package com.tubebox.tv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct InnerTube fallback extractor for videos where NewPipeExtractor's VISIONOS client
 * returns UNPLAYABLE (notably Made-for-Kids videos like cartoons/kids channels, which YouTube
 * blocks on VR/VisionOS clients but serves cleanly on ANDROID_KIDS with HTTP 206).
 */
object InnerTubeFallbackExtractor {

    private data class ClientProfile(
        val name: String,
        val userAgent: String,
        val clientJson: JSONObject
    )

    private val profiles = listOf(
        ClientProfile(
            name = "ANDROID_KIDS",
            userAgent = "com.google.android.apps.youtube.kids/10.10.1 (Linux; U; Android 14) gzip",
            clientJson = JSONObject().apply {
                put("clientName", "ANDROID_KIDS")
                put("clientVersion", "10.10.1")
                put("androidSdkVersion", 34)
                put("osName", "Android")
                put("osVersion", "14")
                put("hl", "pt")
                put("gl", "BR")
            }
        ),
        ClientProfile(
            name = "ANDROID_VR",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientJson = JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.60.19")
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12L")
                put("androidSdkVersion", 32)
                put("hl", "pt")
                put("gl", "BR")
            }
        ),
        ClientProfile(
            name = "IOS",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            clientJson = JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "20.10.4")
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.3.2.22D82")
                put("hl", "pt")
                put("gl", "BR")
            }
        )
    )

    private val VIDEO_ID_REGEX = Regex(
        "(?:v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})"
    )

    fun extractVideoId(url: String): String? {
        if (url.length == 11 && url.matches(Regex("[A-Za-z0-9_-]{11}"))) return url
        return VIDEO_ID_REGEX.find(url)?.groupValues?.getOrNull(1)
    }

    fun extract(videoUrl: String): PlaybackBundle? {
        val videoId = extractVideoId(videoUrl) ?: return null
        val client = OkHttpDownloader.instance.client
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        for (profile in profiles) {
            val bundle = runCatching {
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("context", JSONObject().apply {
                        put("client", profile.clientJson)
                    })
                }.toString()

                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .post(payload.toRequestBody(jsonMediaType))
                    .header("User-Agent", profile.userAgent)
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bodyStr = resp.body?.string() ?: return@runCatching null
                    parsePlayerResponse(videoUrl, JSONObject(bodyStr), profile.userAgent)
                }
            }.getOrNull()

            if (bundle != null && bundle.streamOptions.isNotEmpty()) {
                return bundle
            }
        }
        return null
    }

    private data class RawFormat(
        val url: String,
        val mimeType: String,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val itag: Int
    ) {
        val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)
        val isAudio: Boolean get() = mimeType.startsWith("audio/", ignoreCase = true)
        val isAv1: Boolean get() = mimeType.contains("av01", ignoreCase = true)

        fun codecPriority(): Int = when {
            mimeType.contains("avc1", ignoreCase = true) -> 0
            mimeType.contains("vp9", ignoreCase = true) ||
                mimeType.contains("vp09", ignoreCase = true) -> 1
            mimeType.contains("hev", ignoreCase = true) ||
                mimeType.contains("hvc", ignoreCase = true) -> 2
            else -> 3
        }

        fun shortCodec(): String = when {
            mimeType.contains("avc1", ignoreCase = true) -> "H.264"
            mimeType.contains("vp9", ignoreCase = true) ||
                mimeType.contains("vp09", ignoreCase = true) -> "VP9"
            mimeType.contains("hev", ignoreCase = true) ||
                mimeType.contains("hvc", ignoreCase = true) -> "HEVC"
            mimeType.contains("av01", ignoreCase = true) -> "AV1"
            else -> "MP4"
        }
    }

    private fun parsePlayerResponse(
        originalUrl: String,
        root: JSONObject,
        userAgent: String
    ): PlaybackBundle? {
        val playability = root.optJSONObject("playabilityStatus") ?: return null
        if (!playability.optString("status").equals("OK", ignoreCase = true)) {
            return null
        }

        val streamingData = root.optJSONObject("streamingData") ?: return null
        val videoDetails = root.optJSONObject("videoDetails")

        val title = videoDetails?.optString("title").orEmpty().ifBlank { "Vídeo" }
        val uploader = videoDetails?.optString("author").orEmpty()
        val durationSec = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val isLive = videoDetails?.optBoolean("isLiveContent", false) == true && durationSec == 0L
        val thumbUrl = pickThumbnail(videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))

        val options = mutableListOf<StreamOption>()

        val hlsUrl = streamingData.optString("hlsManifestUrl").orEmpty()
        if (isLive && hlsUrl.isNotBlank()) {
            options.add(
                StreamOption(
                    label = "AO VIVO (Auto)",
                    videoUrl = hlsUrl,
                    audioUrl = null,
                    height = 720,
                    isHls = true,
                    isMuxed = true,
                    codecLabel = "HLS",
                    userAgent = userAgent
                )
            )
        }

        val adaptive = parseFormats(streamingData.optJSONArray("adaptiveFormats"))
        val muxed = parseFormats(streamingData.optJSONArray("formats"))

        val audioFormats = adaptive.filter { it.isAudio }
        val aacAudios = audioFormats.filter {
            it.mimeType.contains("mp4a", ignoreCase = true) || it.mimeType.contains("audio/mp4", ignoreCase = true)
        }
        val bestAudio = (if (aacAudios.isNotEmpty()) aacAudios else audioFormats)
            .maxByOrNull { it.bitrate.coerceAtMost(192_000) }

        if (bestAudio != null) {
            val videoOnly = adaptive.filter { it.isVideo && it.height in 144..1080 }
            val hwFriendly = videoOnly.filter { !it.isAv1 }
            val candidates = if (hwFriendly.isNotEmpty()) hwFriendly else videoOnly

            val byHeight = candidates.groupBy { normalizeHeight(it.height) }
            for (h in byHeight.keys.sortedDescending()) {
                val best = byHeight[h].orEmpty().minWithOrNull(
                    compareBy<RawFormat>(
                        { it.codecPriority() },
                        { if (h >= 1080 && it.fps > 35) 1 else 0 },
                        { -it.bitrate }
                    )
                ) ?: continue

                val fpsTag = if (best.fps >= 50) " 60fps" else ""
                val codecTag = best.shortCodec()
                options.add(
                    StreamOption(
                        label = "${h}p$fpsTag ($codecTag)",
                        videoUrl = best.url,
                        audioUrl = bestAudio.url,
                        height = h,
                        isHls = false,
                        isMuxed = false,
                        codecLabel = codecTag,
                        userAgent = userAgent
                    )
                )
            }
        }

        for (m in muxed.sortedByDescending { it.height }) {
            val h = normalizeHeight(m.height.coerceAtLeast(360))
            val already = options.any { it.height == h && !it.isHls }
            options.add(
                StreamOption(
                    label = if (already) "${h}p (Compatível)" else "${h}p (MP4)",
                    videoUrl = m.url,
                    audioUrl = null,
                    height = h,
                    isHls = false,
                    isMuxed = true,
                    codecLabel = "MP4",
                    userAgent = userAgent
                )
            )
        }

        if (options.isEmpty() && hlsUrl.isNotBlank()) {
            options.add(
                StreamOption(
                    label = "Auto (HLS)",
                    videoUrl = hlsUrl,
                    audioUrl = null,
                    height = 720,
                    isHls = true,
                    isMuxed = true,
                    codecLabel = "HLS",
                    userAgent = userAgent
                )
            )
        }

        if (options.isEmpty()) return null

        val defaultIdx = chooseDefaultIndex(options)
        return PlaybackBundle(
            videoUrl = originalUrl,
            title = title,
            uploader = uploader,
            thumbnailUrl = thumbUrl,
            durationSeconds = durationSec,
            isLive = isLive,
            streamOptions = options,
            defaultIndex = defaultIdx,
            relatedVideos = emptyList()
        )
    }

    private fun parseFormats(arr: JSONArray?): List<RawFormat> {
        if (arr == null) return emptyList()
        val list = mutableListOf<RawFormat>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url").orEmpty()
            if (url.isBlank()) continue
            list.add(
                RawFormat(
                    url = url,
                    mimeType = obj.optString("mimeType").orEmpty(),
                    height = obj.optInt("height", 0),
                    fps = obj.optInt("fps", 30),
                    bitrate = obj.optInt("bitrate", 0),
                    itag = obj.optInt("itag", 0)
                )
            )
        }
        return list
    }

    private fun pickThumbnail(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        var bestUrl = ""
        var bestDist = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url").orEmpty()
            val w = obj.optInt("width", 0)
            if (url.isNotBlank()) {
                val dist = kotlin.math.abs(w - 480)
                if (dist < bestDist) {
                    bestDist = dist
                    bestUrl = url
                }
            }
        }
        return bestUrl
    }

    private fun normalizeHeight(h: Int): Int = when {
        h >= 1000 -> 1080
        h >= 680 -> 720
        h >= 440 -> 480
        h >= 320 -> 360
        h >= 200 -> 240
        else -> 144
    }

    private fun chooseDefaultIndex(options: List<StreamOption>): Int {
        val idx720 = options.indexOfFirst { it.height == 720 }
        if (idx720 >= 0) return idx720
        val below720 = options.withIndex()
            .filter { it.value.height in 360..720 }
            .maxByOrNull { it.value.height }
        return below720?.index ?: 0
    }
}
