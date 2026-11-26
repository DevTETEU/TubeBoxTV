package com.tubebox.tv.data

import com.tubebox.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String
)

object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/DevTETEU/TubeBoxTV/releases/latest"

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TubeBoxTV/${BuildConfig.VERSION_NAME}")
                .build()

            OkHttpDownloader.instance.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) {
                    return@runCatching null
                }

                var apkUrl = json.optString("html_url")
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", apkUrl)
                            break
                        }
                    }
                }

                UpdateInfo(
                    latestVersion = tag,
                    releaseNotes = json.optString("body").take(300),
                    downloadUrl = apkUrl
                )
            }
        }.getOrNull()
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val cParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}
