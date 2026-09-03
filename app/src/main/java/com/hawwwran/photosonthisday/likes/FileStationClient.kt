package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.Allowlist
import com.hawwwran.photosonthisday.api.ApiCall
import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.ApiLog
import com.hawwwran.photosonthisday.api.AppJson
import com.hawwwran.photosonthisday.api.Envelope
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.classifyEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The narrow File Station surface decision 008 permits: download the app's likes file and upload
 * it back. Nothing else. Both go through the allowlist, so an accidental call to any other File
 * Station method throws before a request exists. Photos is never touched here.
 *
 * The session travels in the query and the token in the `X-SYNO-TOKEN` header, which DSM requires
 * on a state-changing call such as the upload. Bodies are read by shape, with the same envelope
 * classifier as the Photos client: a proxy's HTML page is [ApiFailure.Malformed], never success.
 */
class FileStationClient(
    private val http: OkHttpClient,
    private val json: Json = AppJson,
) {
    /**
     * The file's bytes, or null if it does not exist yet. Other failures throw. The file and a
     * File Station error both arrive as HTTP 200, so the body decides: a `{success, ...}` object is
     * an envelope, anything else is the file, which the caller then parses.
     */
    suspend fun download(baseUrl: HttpUrl, path: String, credentials: SessionCredentials): ByteArray? =
        withContext(Dispatchers.IO) {
            val call = Allowlist.FS_DOWNLOAD
            Allowlist.require(call)
            val url = SynologyClient.entryCgi(baseUrl).newBuilder()
                .addQueryParameter("api", call.api)
                .addQueryParameter("version", call.version.toString())
                .addQueryParameter("method", call.method)
                .addQueryParameter("path", path)
                .addQueryParameter("mode", "download")
                .addQueryParameter("_sid", credentials.sid)
                .build()
            val request = Request.Builder().url(url)
                .apply { credentials.synotoken?.let { header(SynologyClient.SYNO_TOKEN_HEADER, it) } }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    // This DSM answers a missing file with HTTP 404 rather than a JSON 408; both mean "not yet".
                    if (response.code == 404) {
                        ApiLog.failure(ApiFailure.DsmError(call, FILE_NOT_FOUND)) // normal on the first run
                        return@use null
                    }
                    if (!response.isSuccessful) throw malformed(call, "HTTP ${response.code}")
                    val bytes = response.body.bytes()
                    when (val envelope = json.classifyEnvelope(bytes.decodeToString())) {
                        is Envelope.NotAnEnvelope -> {
                            ApiLog.ok(call)
                            bytes
                        }
                        is Envelope.Error -> {
                            val failure = ApiFailure.fromDsmCode(call, envelope.code).also(ApiLog::failure)
                            if (envelope.code == FILE_NOT_FOUND) null else throw failure
                        }
                        is Envelope.Success -> throw malformed(call, "download answered an envelope, not a file")
                    }
                }
            } catch (e: IOException) {
                throw ApiFailure.Transport(call, e).also(ApiLog::failure)
            }
        }

    /** Save the file into [folder] as [name], creating the folder if needed, overwriting if present. */
    suspend fun upload(baseUrl: HttpUrl, folder: String, name: String, bytes: ByteArray, credentials: SessionCredentials) =
        withContext(Dispatchers.IO) {
            val call = Allowlist.FS_UPLOAD
            Allowlist.require(call)
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("api", call.api)
                .addFormDataPart("version", call.version.toString())
                .addFormDataPart("method", call.method)
                .addFormDataPart("path", folder)
                .addFormDataPart("create_parents", "true")
                .addFormDataPart("overwrite", "true")
                .addFormDataPart("file", name, bytes.toRequestBody("application/json".toMediaType()))
                .build()
            val url = SynologyClient.entryCgi(baseUrl).newBuilder()
                .addQueryParameter("_sid", credentials.sid)
                .build()
            val request = Request.Builder().url(url).post(multipart)
                .apply { credentials.synotoken?.let { header(SynologyClient.SYNO_TOKEN_HEADER, it) } }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw malformed(call, "HTTP ${response.code}")
                    when (val envelope = json.classifyEnvelope(response.body.string())) {
                        is Envelope.Success -> ApiLog.ok(call)
                        is Envelope.Error -> throw ApiFailure.fromDsmCode(call, envelope.code).also(ApiLog::failure)
                        // A body that is not an envelope is not a confirmation: the file may never have been written.
                        is Envelope.NotAnEnvelope -> throw malformed(call, envelope.detail)
                    }
                }
            } catch (e: IOException) {
                throw ApiFailure.Transport(call, e).also(ApiLog::failure)
            }
        }

    private fun malformed(call: ApiCall, detail: String): ApiFailure.Malformed =
        ApiFailure.Malformed(call, detail).also(ApiLog::failure)

    private companion object {
        const val FILE_NOT_FOUND = 408 // File Station: no such file or directory
    }
}
