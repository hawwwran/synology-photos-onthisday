package com.hawwwran.photosonthisday.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hawwwran.photosonthisday.api.Space
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream

sealed interface SaveResult {
    data object Success : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Saves the original file to the gallery through MediaStore, so it lands there with no storage
 * permission on API 29+ (the pre-29 path is covered by the manifest's scoped
 * WRITE_EXTERNAL_STORAGE). The bytes are the original, not a rendition, and are streamed rather
 * than buffered, because an original can be a large video.
 */
class ImageSaver(
    private val context: Context,
    private val http: OkHttpClient,
) {
    suspend fun save(baseUrl: HttpUrl, space: Space, unitId: Int, sid: String, token: String?): SaveResult {
        val reason = OriginalFetch.fetch(http, baseUrl, space, unitId, sid, token) { mime, source ->
            writeToGallery(unitId, mime, source)
        }
        return if (reason == null) SaveResult.Success else SaveResult.Failed(reason)
    }

    // Called on the IO dispatcher inside OriginalFetch.fetch, so it is a plain blocking write.
    private fun writeToGallery(unitId: Int, contentType: String, source: InputStream) {
        val isVideo = contentType.startsWith("video/")
        val name = "OnThisDay-$unitId.${extensionForMime(contentType)}"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, contentType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/On This Day")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the insert")
        resolver.openOutputStream(uri)?.use { out -> source.use { it.copyTo(out) } } ?: throw IOException("no output stream")
    }
}
