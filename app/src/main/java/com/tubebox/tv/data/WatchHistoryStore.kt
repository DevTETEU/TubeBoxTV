package com.tubebox.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WatchHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("tubebox_history", Context.MODE_PRIVATE)

    fun getAll(): List<VideoItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<VideoItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VideoItem(
                        url = obj.getString("url"),
                        title = obj.getString("title"),
                        uploader = obj.optString("uploader", ""),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        durationSeconds = obj.optLong("durationSeconds", 0L),
                        viewCount = obj.optLong("viewCount", -1L),
                        isLive = obj.optBoolean("isLive", false)
                    )
                )
            }
            list
        }.getOrElse { emptyList() }
    }

    fun record(item: VideoItem) {
        val current = getAll().filterNot { it.url == item.url }.toMutableList()
        current.add(0, item)
        val trimmed = current.take(MAX_ITEMS)

        val arr = JSONArray()
        for (v in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("url", v.url)
                    put("title", v.title)
                    put("uploader", v.uploader)
                    put("thumbnailUrl", v.thumbnailUrl)
                    put("durationSeconds", v.durationSeconds)
                    put("viewCount", v.viewCount)
                    put("isLive", v.isLive)
                }
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items_v1"
        private const val MAX_ITEMS = 40
    }
}
