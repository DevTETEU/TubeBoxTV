package com.tubebox.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

object YouTubeRepository {

    const val KIOSK_MUSIC = "trending_music"
    const val KIOSK_GAMING = "trending_gaming"
    const val KIOSK_LIVE = "live"
    const val KIOSK_PODCASTS = "trending_podcasts_episodes"

    private val ytService
        get() = NewPipe.getService(ServiceList.YouTube.serviceId)

    suspend fun loadHomeFeed(): FeedPage = withContext(Dispatchers.IO) {
        // Combine active Brazilian kiosks so Home is always rich and fresh
        val combined = mutableListOf<VideoItem>()
        val seenUrls = hashSetOf<String>()

        val kioskOrder = listOf(KIOSK_MUSIC, KIOSK_GAMING, KIOSK_LIVE)
        for (kioskId in kioskOrder) {
            runCatching {
                val extractor = ytService.kioskList.getExtractorById(kioskId, null)
                val info = KioskInfo.getInfo(extractor)
                info.relatedItems.take(12).forEach { item ->
                    val mapped = item.toVideoItem()
                    if (mapped != null && seenUrls.add(mapped.url)) {
                        combined.add(mapped)
                    }
                }
            }
        }

        // Fallback if kiosks return empty
        if (combined.isEmpty()) {
            return@withContext search("brasil videos em alta 2026", null)
        }

        FeedPage(items = combined, nextPage = null)
    }

    suspend fun loadKiosk(kioskId: String, nextPage: Page? = null): FeedPage =
        withContext(Dispatchers.IO) {
            val service = ytService
            val factory = service.kioskList.getListLinkHandlerFactoryByType(kioskId)
            val kioskUrl = factory.getUrl(kioskId)

            if (nextPage == null) {
                val extractor = service.kioskList.getExtractorById(kioskId, null)
                val info = KioskInfo.getInfo(extractor)
                val items = info.relatedItems.mapNotNull { it.toVideoItem() }
                FeedPage(items = items, nextPage = info.nextPage)
            } else {
                val more = KioskInfo.getMoreItems(service, kioskUrl, nextPage)
                val items = more.items.mapNotNull { it.toVideoItem() }
                FeedPage(items = items, nextPage = more.nextPage)
            }
        }

    suspend fun search(query: String, nextPage: Page? = null): FeedPage =
        withContext(Dispatchers.IO) {
            val service = ytService
            val qh = service.searchQHFactory.fromQuery(query.trim(), emptyList(), "")

            if (nextPage == null) {
                val info = SearchInfo.getInfo(service, qh)
                val items = info.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toVideoItem() }
                FeedPage(items = items, nextPage = info.nextPage)
            } else {
                val more = SearchInfo.getMoreItems(service, qh, nextPage)
                val items = more.items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toVideoItem() }
                FeedPage(items = items, nextPage = more.nextPage)
            }
        }

    suspend fun loadStream(videoUrl: String): PlaybackBundle = withContext(Dispatchers.IO) {
        val primaryResult = runCatching { extractWithNewPipe(videoUrl) }
        primaryResult.getOrNull()?.let { return@withContext it }

        // Fallback: InnerTube ANDROID_KIDS -> ANDROID_VR -> IOS (fixes Made-for-Kids videos!)
        val fallback = InnerTubeFallbackExtractor.extract(videoUrl)
        if (fallback != null) {
            val related = if (fallback.uploader.isNotBlank() || fallback.title.isNotBlank()) {
                val query = fallback.uploader.ifBlank { fallback.title.take(40) }
                runCatching {
                    search(query, null).items
                        .filterNot { it.url == videoUrl || it.url == fallback.videoUrl }
                        .take(15)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            return@withContext fallback.copy(relatedVideos = related)
        }

        throw (primaryResult.exceptionOrNull()
            ?: IllegalStateException("Nenhum fluxo de vídeo compatível encontrado."))
    }

    suspend fun loadStreamFallbackOnly(videoUrl: String): PlaybackBundle? =
        withContext(Dispatchers.IO) {
            InnerTubeFallbackExtractor.extract(videoUrl)
        }

    private fun extractWithNewPipe(videoUrl: String): PlaybackBundle {
        val info = StreamInfo.getInfo(ytService, videoUrl)
        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM

        val options = mutableListOf<StreamOption>()

        // 1. Live streams: prefer HLS
        if (isLive && !info.hlsUrl.isNullOrBlank()) {
            options.add(
                StreamOption(
                    label = "AO VIVO (Auto)",
                    videoUrl = info.hlsUrl,
                    audioUrl = null,
                    height = 720,
                    isHls = true,
                    isMuxed = true,
                    codecLabel = "HLS"
                )
            )
        }

        // 2. Pick best RK3229-friendly audio stream (prefer M4A / AAC over Opus)
        val bestAudio = pickBestAudio(info.audioStreams)

        // 3. Adaptive video-only streams paired with best audio
        //    RK3229 hardware decoders: AVC (H.264) & VP9. Avoid AV1 (software only, too heavy!)
        if (bestAudio != null && !bestAudio.content.isNullOrBlank()) {
            val validVideoOnly = info.videoOnlyStreams
                .filter { it.isUrl && !it.content.isNullOrBlank() && it.height in 144..1080 }

            val hwFriendly = validVideoOnly.filter { !isAv1(it) }
            val candidates = if (hwFriendly.isNotEmpty()) hwFriendly else validVideoOnly

            // Group by height and pick the most RK3229-compatible codec per resolution
            val byHeight = candidates.groupBy { normalizeHeight(it.height) }
            val sortedHeights = byHeight.keys.sortedDescending()

            for (h in sortedHeights) {
                val group = byHeight[h].orEmpty()
                val best = group.minWithOrNull(
                    compareBy<VideoStream>(
                        { codecPriority(it) },
                        // On RK3229, 30fps is smoother than 60fps at 1080p
                        { if (h >= 1080 && it.fps > 35) 1 else 0 },
                        { -it.bitrate }
                    )
                ) ?: continue

                val codecTag = shortCodecName(best)
                val fpsTag = if (best.fps >= 50) " 60fps" else ""
                options.add(
                    StreamOption(
                        label = "${h}p$fpsTag ($codecTag)",
                        videoUrl = best.content,
                        audioUrl = bestAudio.content,
                        height = h,
                        isHls = false,
                        isMuxed = false,
                        codecLabel = codecTag
                    )
                )
            }
        }

        // 4. Progressive muxed video+audio streams (reliable fallback, e.g., 360p/720p MP4)
        val muxedStreams = info.videoStreams
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .sortedByDescending { it.height }

        for (stream in muxedStreams) {
            val h = normalizeHeight(stream.height.coerceAtLeast(360))
            val alreadyHasHeight = options.any { it.height == h && !it.isHls }
            val label = if (alreadyHasHeight) "${h}p (Compatível)" else "${h}p (MP4)"
            options.add(
                StreamOption(
                    label = label,
                    videoUrl = stream.content,
                    audioUrl = null,
                    height = h,
                    isHls = false,
                    isMuxed = true,
                    codecLabel = "MP4"
                )
            )
        }

        // 5. Fallback HLS for non-live if nothing else extracted
        if (options.isEmpty() && !info.hlsUrl.isNullOrBlank()) {
            options.add(
                StreamOption(
                    label = "Auto (HLS)",
                    videoUrl = info.hlsUrl,
                    audioUrl = null,
                    height = 720,
                    isHls = true,
                    isMuxed = true,
                    codecLabel = "HLS"
                )
            )
        }

        if (options.isEmpty()) {
            throw IllegalStateException("Nenhum fluxo de vídeo compatível encontrado.")
        }

        // Default to 720p (matches TV Box 1280x720 display & runs effortlessly on RK3229)
        val defaultIdx = chooseDefaultIndex(options)

        val related = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toVideoItem() }
            .take(20)

        return PlaybackBundle(
            videoUrl = info.url ?: videoUrl,
            title = info.name ?: "Vídeo",
            uploader = info.uploaderName ?: "",
            thumbnailUrl = pickBestThumbnail(info.thumbnails),
            durationSeconds = info.duration,
            isLive = isLive,
            streamOptions = options,
            defaultIndex = defaultIdx,
            relatedVideos = related
        )
    }

    private fun chooseDefaultIndex(options: List<StreamOption>): Int {
        // Prefer 720p H.264/VP9 adaptive or muxed
        val idx720 = options.indexOfFirst { it.height == 720 }
        if (idx720 >= 0) return idx720

        // Next prefer highest quality <= 720p (e.g., 480p or 360p)
        val below720 = options.withIndex()
            .filter { it.value.height in 360..720 }
            .maxByOrNull { it.value.height }
        if (below720 != null) return below720.index

        // Otherwise first option (e.g., 1080p or HLS)
        return 0
    }

    private fun pickBestAudio(streams: List<AudioStream>): AudioStream? {
        val valid = streams.filter { it.isUrl && !it.content.isNullOrBlank() }
        if (valid.isEmpty()) return null

        // Prefer M4A / AAC around 128kbps (hardware-friendly on Android 7.1.2)
        val aac = valid.filter {
            val codec = (it.codec ?: "").lowercase()
            val mime = (it.format?.mimeType ?: "").lowercase()
            codec.contains("mp4a") || codec.contains("aac") || mime.contains("mp4") || mime.contains("m4a")
        }
        val pool = if (aac.isNotEmpty()) aac else valid
        return pool.maxByOrNull { it.averageBitrate.coerceAtMost(192) } ?: pool.first()
    }

    private fun isAv1(stream: VideoStream): Boolean {
        val codec = (stream.codec ?: "").lowercase()
        return codec.startsWith("av01") || codec.contains("av1")
    }

    private fun codecPriority(stream: VideoStream): Int {
        val codec = (stream.codec ?: "").lowercase()
        return when {
            codec.startsWith("avc") || codec.contains("h264") -> 0 // Best on RK3229
            codec.startsWith("vp9") || codec.startsWith("vp09") -> 1 // Also HW decoded on RK3229
            codec.startsWith("hev") || codec.startsWith("hvc") -> 2
            else -> 3
        }
    }

    private fun shortCodecName(stream: VideoStream): String {
        val codec = (stream.codec ?: "").lowercase()
        return when {
            codec.startsWith("avc") || codec.contains("h264") -> "H.264"
            codec.startsWith("vp9") || codec.startsWith("vp09") -> "VP9"
            codec.startsWith("hev") || codec.startsWith("hvc") -> "HEVC"
            codec.startsWith("av01") -> "AV1"
            else -> stream.format?.name ?: "MP4"
        }
    }

    private fun normalizeHeight(h: Int): Int = when {
        h >= 1000 -> 1080
        h >= 680 -> 720
        h >= 440 -> 480
        h >= 320 -> 360
        h >= 200 -> 240
        else -> 144
    }

    private fun StreamInfoItem.toVideoItem(): VideoItem? {
        val url = this.url ?: return null
        val name = this.name ?: return null
        val isLive = this.streamType == StreamType.LIVE_STREAM ||
            this.streamType == StreamType.AUDIO_LIVE_STREAM
        return VideoItem(
            url = url,
            title = name,
            uploader = this.uploaderName ?: "",
            thumbnailUrl = pickBestThumbnail(this.thumbnails),
            durationSeconds = this.duration,
            viewCount = this.viewCount,
            isLive = isLive,
            uploadDate = this.textualUploadDate ?: ""
        )
    }

    private fun pickBestThumbnail(images: List<Image>): String {
        if (images.isEmpty()) return ""
        // Prefer ~320-480px width thumbnail: crisp on 1280x720 3-col grid & low RAM on RK3229
        val ideal = images
            .filter { it.url.isNotBlank() && it.width in 300..640 }
            .minByOrNull { kotlin.math.abs(it.width - 480) }
        if (ideal != null) return ideal.url

        return images
            .filter { it.url.isNotBlank() }
            .maxByOrNull { it.width }
            ?.url
            ?: images.first().url
    }
}
