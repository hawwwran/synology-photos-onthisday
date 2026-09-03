package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.Allowlist
import com.hawwwran.photosonthisday.api.ApiLog
import com.hawwwran.photosonthisday.api.DownloadUrls
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.api.SynologyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Why an original could not be fetched and written. The screen maps each to its own text. */
enum class FetchFailure {
    /** The NAS answered, but not with an image or a video: the session or the token was wrong. */
    NOT_A_FILE,

    /** No answer, or the connection broke while the bytes were streaming. */
    TRANSPORT,

    /** The NAS delivered; writing on this device failed (MediaStore refused, disk full, ...). */
    LOCAL,
}

/** The one place the original file is fetched: `SYNO.Foto.Download`, session in the query, token in the header. */
internal object OriginalFetch {
    /**
     * Streams the original to [onBody] as (mime, source). Returns null on success or the
     * [FetchFailure] that applies. A failure inside [onBody] is [FetchFailure.LOCAL] unless the
     * stream itself broke, which the wrapped source tags as transport; before this split a full
     * disk was reported as "NAS není dostupný" although the NAS had answered. The body is never
     * logged, only the call name, the code and the type.
     */
    suspend fun fetch(
        http: OkHttpClient,
        baseUrl: HttpUrl,
        space: Space,
        unitId: Int,
        sid: String,
        token: String?,
        onBody: (mime: String, source: InputStream) -> Unit,
    ): FetchFailure? = withContext(Dispatchers.IO) {
        val call = Allowlist.download(space)
        // The session rides in the query here: the cookie form was verified only for thumbnails
        // (research, "Update, second run"), and this is what was observed to work for the download.
        val request = Request.Builder()
            .url(DownloadUrls.original(baseUrl, space, unitId, sid))
            .apply { if (!token.isNullOrEmpty()) header(SynologyClient.SYNO_TOKEN_HEADER, token) }
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            ApiLog.transport(call, e)
            return@withContext FetchFailure.TRANSPORT
        }
        response.use {
            val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (!response.isSuccessful || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
                ApiLog.malformed(call, "HTTP ${response.code}, type '$mime'")
                return@withContext FetchFailure.NOT_A_FILE
            }
            try {
                onBody(mime, NetworkInputStream(response.body.byteStream()))
                ApiLog.ok(call)
                null
            } catch (e: NetworkReadException) {
                ApiLog.transport(call, e.network)
                FetchFailure.TRANSPORT
            } catch (e: IOException) {
                FetchFailure.LOCAL
            }
        }
    }
}

/** A read failure on the response stream, as opposed to one on whatever [OriginalFetch.fetch]'s caller writes to. */
private class NetworkReadException(val network: IOException) : IOException(network)

private class NetworkInputStream(source: InputStream) : FilterInputStream(source) {
    override fun read(): Int = try {
        super.read()
    } catch (e: IOException) {
        throw NetworkReadException(e)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = try {
        super.read(b, off, len)
    } catch (e: IOException) {
        throw NetworkReadException(e)
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
