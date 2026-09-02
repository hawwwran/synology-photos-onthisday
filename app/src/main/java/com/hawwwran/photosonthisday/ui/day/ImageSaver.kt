package com.hawwwran.photosonthisday.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailUrls
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
 * Saves a photo to the device's Pictures collection through MediaStore, so it lands in the
 * gallery without any storage permission on API 29+ (minSdk is 26; the pre-29 path writes to
 * the public Pictures directory, which the legacy WRITE permission covers if present).
 *
 * This saves the largest rendition the observed API serves (`xl`). The byte-exact original needs
 * `SYNO.Foto.Download`, which plan 001 did not observe and this project does not guess; see the
 * blocked task in plan 005.
 */
class ImageSaver(
    private val context: Context,
    private val http: OkHttpClient,
) {
    suspend fun save(baseUrl: HttpUrl, ref: ThumbnailRef, sid: String, token: String?): SaveResult =
        withContext(Dispatchers.IO) {
            val url = ThumbnailUrls.get(baseUrl, ref, sid)
            val request = Request.Builder().url(url)
                .apply { if (!token.isNullOrEmpty()) header(SynologyClient.SYNO_TOKEN_HEADER, token) }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body
                    val type = response.header("Content-Type").orEmpty()
                    if (!response.isSuccessful || !type.startsWith("image/")) {
                        return@withContext SaveResult.Failed("The NAS did not return an image.")
                    }
                    writeToPictures(ref, type, body.bytes())
                }
                SaveResult.Success
            } catch (e: IOException) {
                SaveResult.Failed("Could not reach the NAS.")
            }
        }

    private fun writeToPictures(ref: ThumbnailRef, contentType: String, bytes: ByteArray) {
        val extension = if ("png" in contentType) "png" else "jpg"
        val name = "OnThisDay-${ref.unitId}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (extension == "png") "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/On This Day")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the insert")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("no output stream")
    }
}
