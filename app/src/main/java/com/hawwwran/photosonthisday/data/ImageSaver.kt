package com.hawwwran.photosonthisday.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.hawwwran.photosonthisday.api.Space
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream

sealed interface SaveResult {
    data object Success : SaveResult
    data class Failed(val reason: FetchFailure) : SaveResult
}

/**
 * Saves the original file to the gallery through MediaStore, which on API 29+ (minSdk) needs no
 * storage permission. The bytes are the original, not a rendition, and are streamed rather than
 * buffered, because an original can be a large video. The row is inserted as pending and
 * published only after the copy completes, so a failed copy never leaves a broken, gallery-visible
 * file; an item already saved is not saved twice.
 */
class ImageSaver(
    private val context: Context,
    private val http: OkHttpClient,
) {
    suspend fun save(baseUrl: HttpUrl, space: Space, unitId: Int, sid: String, token: String?): SaveResult {
        val failure = OriginalFetch.fetch(http, baseUrl, space, unitId, sid, token) { mime, source ->
            writeToGallery(unitId, mime, source)
        }
        return if (failure == null) SaveResult.Success else SaveResult.Failed(failure)
    }

    // Called on the IO dispatcher inside OriginalFetch.fetch, so it is a plain blocking write.
    private fun writeToGallery(unitId: Int, contentType: String, source: InputStream) {
        val isVideo = contentType.startsWith("video/")
        val name = "OnThisDay-$unitId.${extensionForMime(contentType)}"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val relativePath = "$folder/$ALBUM/"
        val resolver = context.contentResolver
        if (exists(resolver, collection, name, relativePath)) {
            source.close()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, contentType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the insert")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.use { it.copyTo(out) } }
                ?: throw IOException("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** A row this app already wrote under the same name and folder; MediaStore stores the path with a trailing slash. */
    private fun exists(resolver: ContentResolver, collection: Uri, name: String, relativePath: String): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, relativePath),
            null,
        )?.use { it.count > 0 } ?: false

    private companion object {
        const val ALBUM = "On This Day"
    }
}
