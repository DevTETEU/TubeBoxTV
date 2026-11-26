package com.tubebox.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

object YouTubeRepository {

    const val CATEGORY_HOME = "home"
    const val CATEGORY_MUSIC = "music"
    const val CATEGORY_GAMING = "gaming"
    const val CATEGORY_LIVE = "live"
    const val CATEGORY_PODCASTS = "podcasts"

    private val categoryQueries = mapOf(
        CATEGORY_HOME to "vídeos em alta brasil",
        CATEGORY_MUSIC to "músicas mais tocadas brasil clipes oficiais",
        CATEGORY_GAMING to "gameplay jogos brasil",
        CATEGORY_LIVE to "ao vivo agora brasil",
        CATEGORY_PODCASTS to "podcast brasil episódio completo"
    )

    private val ytService
        get() = NewPipe.getService(ServiceList.YouTube.serviceId)

    suspend fun loadCategory(categoryId: String, nextPage: Page? = null): FeedPage =
        withContext(Dispatchers.IO) {
            val query = categoryQueries[categoryId] ?: categoryQueries.getValue(CATEGORY_HOME)
            val page = search(query, nextPage)

            // For the Live tab, float active live streams to the top of the first page
            if (categoryId == CATEGORY_LIVE && nextPage == null) {
                val sorted = page.items.sortedByDescending { it.isLive }
                page.copy(items = sorted)
            } else {
                page
            }
        }

    suspend fun search(query: String, nextPage: Page? = null): FeedPage =
        withContext(Dispatchers.IO) {
            val service = ytService
            val cleanQuery = query.trim()
            val qh = service.searchQHFactory.fromQuery(cleanQuery, listOf("videos"), "")

            if (nextPage == null) {
                val info = SearchInfo.getInfo(service, qh)
                val items = info.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toVideoItem() }
                if (items.isNotEmpty()) {
                    FeedPage(items = items, nextPage = info.nextPage)
                } else {
                    // Fallback without filter if strict "videos" filter returned empty
                    val fallbackQh = service.searchQHFactory.fromQuery(cleanQuery, emptyList(), "")
                    val fallbackInfo = SearchInfo.getInfo(service, fallbackQh)
                    val fallbackItems = fallbackInfo.relatedItems
                        .filterIsInstance<StreamInfoItem>()
                        .mapNotNull { it.toVideoItem() }
                    FeedPage(items = fallbackItems, nextPage = fallbackInfo.nextPage)
                }
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
