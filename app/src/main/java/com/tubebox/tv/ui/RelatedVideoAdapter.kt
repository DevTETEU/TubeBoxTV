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

class RelatedVideoAdapter(
    private val onVideoSelected: (VideoItem) -> Unit
) : RecyclerView.Adapter<RelatedVideoAdapter.RelViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_related_video, parent, false)
        return RelViewHolder(view)
    }

    override fun onBindViewHolder(holder: RelViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class RelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivRelThumb)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvRelDuration)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvRelTitle)
        private val tvChannel: TextView = itemView.findViewById(R.id.tvRelChannel)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
                v.z = if (hasFocus) 8f else 0f
            }
        }

        fun bind(item: VideoItem) {
            tvTitle.text = item.title
            tvChannel.text = item.uploader
            tvDuration.text = item.formattedDuration()
            tvDuration.setBackgroundResource(
                if (item.isLive) R.drawable.bg_live_badge else R.drawable.bg_duration_badge
            )
            ivThumb.load(item.thumbnailUrl) {
                crossfade(false)
                size(320, 180)
            }
            itemView.setOnClickListener { onVideoSelected(item) }
        }
    }
}
