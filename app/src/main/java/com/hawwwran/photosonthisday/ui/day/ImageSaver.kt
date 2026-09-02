package com.hawwwran.photosonthisday.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.hawwwran.photosonthisday.api.DownloadUrls
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.api.SynologyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface SaveResult {
    data object Success : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Saves the original file to the device's gallery through MediaStore, so it lands there with no
 * storage permission on API 29+ (the pre-29 path is covered by the manifest's scoped
 * WRITE_EXTERNAL_STORAGE). The bytes come from `SYNO.Foto.Download`, which returns the original
 * (research, download probe), so this is the real file, not a rendition. The session travels in
 * a cookie and the token in its header, so the request URL carries nothing secret.
 *
 * The response is streamed, not buffered, because an original can be a large video.
 */
class ImageSaver(
    private val context: Context,
    private val http: OkHttpClient,
) {
    suspend fun save(baseUrl: HttpUrl, space: Space, unitId: Int, sid: String, token: String?): SaveResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(DownloadUrls.original(baseUrl, space, unitId, sid))
                .apply { if (!token.isNullOrEmpty()) header(SynologyClient.SYNO_TOKEN_HEADER, token) }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val type = response.header("Content-Type").orEmpty().substringBefore(';').trim()
                    if (!response.isSuccessful || !(type.startsWith("image/") || type.startsWith("video/"))) {
                        // Safe to log: the call name, the code and the type; never the body.
                        Log.w("PhotosApi", "${space.apiPrefix}.Download: HTTP ${response.code}, type '$type'")
                        return@withContext SaveResult.Failed("NAS nevrátil soubor.")
                    }
                    val body = response.body ?: return@withContext SaveResult.Failed("Prázdná odpověď.")
                    writeToGallery(unitId, type, body.byteStream())
                }
                SaveResult.Success
            } catch (e: IOException) {
                SaveResult.Failed("NAS není dostupný.")
            }
        }

    private fun writeToGallery(unitId: Int, contentType: String, source: java.io.InputStream) {
        val isVideo = contentType.startsWith("video/")
        val extension = EXTENSIONS[contentType] ?: if (isVideo) "mp4" else "jpg"
        val name = "OnThisDay-$unitId.$extension"
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
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
        resolver.openOutputStream(uri)?.use { out -> source.use { it.copyTo(out) } }
            ?: throw IOException("no output stream")
    }

    private companion object {
        val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/heic" to "heic",
            "image/heif" to "heic",
            "image/webp" to "webp",
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
        )
    }
}
