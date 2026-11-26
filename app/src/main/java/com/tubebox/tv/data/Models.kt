package com.tubebox.tv.data

import org.schabi.newpipe.extractor.Page

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val viewCount: Long,
    val isLive: Boolean,
    val uploadDate: String = ""
) {
    fun formattedDuration(): String {
        if (isLive || durationSeconds < 0) return "AO VIVO"
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun formattedMetadata(): String {
        val parts = mutableListOf<String>()
        if (uploader.isNotBlank()) parts.add(uploader)
        if (viewCount > 0) {
            val viewsStr = when {
                viewCount >= 1_000_000_000L -> String.format("%.1f bi", viewCount / 1_000_000_000.0)
                viewCount >= 1_000_000L -> String.format("%.1f mi", viewCount / 1_000_000.0)
                viewCount >= 1_000L -> String.format("%.1f mil", viewCount / 1_000.0)
                else -> viewCount.toString()
            }.replace(",0 ", " ").replace(".0 ", " ")
            parts.add(if (isLive) "$viewsStr assistindo" else "$viewsStr views")
        }
        return parts.joinToString(" • ")
    }
}

data class FeedPage(
    val items: List<VideoItem>,
    val nextPage: Page?
)

data class StreamOption(
    val label: String,
    val videoUrl: String,
    val audioUrl: String?,
    val height: Int,
    val isHls: Boolean = false,
    val isMuxed: Boolean = false,
    val codecLabel: String = "",
    val userAgent: String = OkHttpDownloader.USER_AGENT
)

data class PlaybackBundle(
    val videoUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val isLive: Boolean,
    val streamOptions: List<StreamOption>,
    val defaultIndex: Int,
    val relatedVideos: List<VideoItem>
)
