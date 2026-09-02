package com.hawwwran.photosonthisday.data

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
import java.io.InputStream

/** The one place the original file is fetched: `SYNO.Foto.Download`, session in the query, token in the header. */
internal object OriginalFetch {
    /**
     * Streams the original to [onBody] as (mime, source). Returns null on success, or a plain
     * reason to show. A non-image, non-video answer means the token or session was wrong; the
     * body is never logged, only the call name, the code and the type.
     */
    suspend fun fetch(
        http: OkHttpClient,
        baseUrl: HttpUrl,
        space: Space,
        unitId: Int,
        sid: String,
        token: String?,
        onBody: (mime: String, source: InputStream) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(DownloadUrls.original(baseUrl, space, unitId, sid))
            .apply { if (!token.isNullOrEmpty()) header(SynologyClient.SYNO_TOKEN_HEADER, token) }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim()
                if (!response.isSuccessful || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
                    Log.w("PhotosApi", "${space.apiPrefix}.Download: HTTP ${response.code}, type '$mime'")
                    return@withContext "NAS nevrátil soubor."
                }
                val body = response.body ?: return@withContext "Prázdná odpověď."
                onBody(mime, body.byteStream())
            }
            null
        } catch (e: IOException) {
            "NAS není dostupný."
        }
    }
}

/** File extension for a served mime type, defaulting by whether it is video or image. */
internal fun extensionForMime(mime: String): String = when (mime) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/heic", "image/heif" -> "heic"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    else -> if (mime.startsWith("video/")) "mp4" else "jpg"
}
