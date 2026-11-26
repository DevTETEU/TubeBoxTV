package com.tubebox.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tubebox.tv.R
import com.tubebox.tv.data.VideoItem

class VideoGridAdapter(
    private val onVideoClick: (VideoItem) -> Unit,
    private val onNearEnd: () -> Unit
) : RecyclerView.Adapter<VideoGridAdapter.VideoViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendItems(moreItems: List<VideoItem>) {
        if (moreItems.isEmpty()) return
        val existingUrls = items.asSequence().map { it.url }.toHashSet()
        val filtered = moreItems.filter { existingUrls.add(it.url) }
        if (filtered.isEmpty()) return
        val start = items.size
        items.addAll(filtered)
        notifyItemRangeInserted(start, filtered.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)

        if (position >= items.size - 6) {
            onNearEnd()
        }
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.035f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(110)
                    .start()
                v.z = if (hasFocus) 8f else 0f
            }
        }

        fun bind(item: VideoItem) {
            tvTitle.text = item.title
            tvMeta.text = item.formattedMetadata()
            tvDuration.text = item.formattedDuration()
            tvDuration.setBackgroundResource(
                if (item.isLive) R.drawable.bg_live_badge else R.drawable.bg_duration_badge
            )

            ivThumbnail.load(item.thumbnailUrl) {
                crossfade(false)
                size(480, 270)
            }

            itemView.setOnClickListener { onVideoClick(item) }
        }
    }
}
