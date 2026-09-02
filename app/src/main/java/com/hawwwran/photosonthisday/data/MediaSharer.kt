package com.hawwwran.photosonthisday.data

import android.content.Context
import com.hawwwran.photosonthisday.api.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File

sealed interface ShareResult {
    /** The temp copy to hand to the share sheet, and its mime type. */
    data class Ready(val file: File, val mime: String) : ShareResult
    data class Failed(val reason: String) : ShareResult
}

/**
 * Prepares a photo or video for the Android share sheet: it downloads the original into a temp
 * cache directory, which a FileProvider exposes as a content:// URI. Temp copies older than an
 * hour are swept on each share, so the cache does not grow.
 */
class MediaSharer(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val dir: File get() = File(context.cacheDir, SHARE_DIR)

    suspend fun prepare(baseUrl: HttpUrl, space: Space, unitId: Int, sid: String, token: String?): ShareResult =
        withContext(Dispatchers.IO) {
            cleanOld()
            dir.mkdirs()
            var file: File? = null
            var mime = "application/octet-stream"
            val reason = OriginalFetch.fetch(http, baseUrl, space, unitId, sid, token) { contentType, source ->
                mime = contentType
                val target = File(dir, "OnThisDay-$unitId.${extensionForMime(contentType)}")
                target.outputStream().use { source.copyTo(it) }
                file = target
            }
            val ready = file
            if (reason == null && ready != null) ShareResult.Ready(ready, mime) else ShareResult.Failed(reason ?: "Sdílení selhalo.")
        }

    /** Delete temp copies older than an hour. */
    private fun cleanOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private companion object {
        const val SHARE_DIR = "shared"
        const val MAX_AGE_MS = 60L * 60L * 1000L
    }
}
