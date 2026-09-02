package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.Allowlist
import com.hawwwran.photosonthisday.api.ApiLog
import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.api.SynologyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * on a state-changing call such as the upload.
 */
class FileStationClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** The file's bytes, or null if it does not exist yet. Other failures throw. */
    suspend fun download(baseUrl: HttpUrl, path: String, credentials: SessionCredentials): ByteArray? =
        withContext(Dispatchers.IO) {
            Allowlist.require(Allowlist.FS_DOWNLOAD)
            val url = SynologyClient.entryCgi(baseUrl).newBuilder()
                .addQueryParameter("api", Allowlist.FS_DOWNLOAD.api)
                .addQueryParameter("version", Allowlist.FS_DOWNLOAD.version.toString())
                .addQueryParameter("method", Allowlist.FS_DOWNLOAD.method)
                .addQueryParameter("path", path)
                .addQueryParameter("mode", "download")
                .addQueryParameter("_sid", credentials.sid)
                .build()
            val request = Request.Builder().url(url)
                .apply { credentials.synotoken?.let { header(SynologyClient.SYNO_TOKEN_HEADER, it) } }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    // This DSM answers a missing file with HTTP 404 rather than a JSON 408; treat both as "not yet".
                    if (response.code == 404) {
                        ApiLog.dsmError(Allowlist.FS_DOWNLOAD, FILE_NOT_FOUND)
                        return@use null
                    }
                    if (!response.isSuccessful) {
                        ApiLog.malformed(Allowlist.FS_DOWNLOAD, "HTTP ${response.code}")
                        throw ApiFailure.Malformed(Allowlist.FS_DOWNLOAD, "HTTP ${response.code}")
                    }
                    val body = response.body ?: throw ApiFailure.Malformed(Allowlist.FS_DOWNLOAD, "empty body")
                    // A real download carries a Content-Disposition; a File Station error is JSON without one.
                    if (response.header("Content-Disposition") != null) {
                        ApiLog.ok(Allowlist.FS_DOWNLOAD)
                        body.bytes()
                    } else {
                        val code = errorCode(body.string())
                        if (code == FILE_NOT_FOUND) {
                            ApiLog.dsmError(Allowlist.FS_DOWNLOAD, FILE_NOT_FOUND) // normal on the first run
                            null
                        } else {
                            ApiLog.dsmError(Allowlist.FS_DOWNLOAD, code ?: -1)
                            throw ApiFailure.DsmError(Allowlist.FS_DOWNLOAD, code ?: -1)
                        }
                    }
                }
            } catch (e: IOException) {
                ApiLog.transport(Allowlist.FS_DOWNLOAD, e)
                throw ApiFailure.Transport(Allowlist.FS_DOWNLOAD, e)
            }
        }

    /** Save the file into [folder] as [name], creating the folder if needed, overwriting if present. */
    suspend fun upload(baseUrl: HttpUrl, folder: String, name: String, bytes: ByteArray, credentials: SessionCredentials) =
        withContext(Dispatchers.IO) {
            Allowlist.require(Allowlist.FS_UPLOAD)
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("api", Allowlist.FS_UPLOAD.api)
                .addFormDataPart("version", Allowlist.FS_UPLOAD.version.toString())
                .addFormDataPart("method", Allowlist.FS_UPLOAD.method)
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
                    if (!response.isSuccessful) {
                        ApiLog.malformed(Allowlist.FS_UPLOAD, "HTTP ${response.code}")
                        throw ApiFailure.Malformed(Allowlist.FS_UPLOAD, "HTTP ${response.code}")
                    }
                    val code = errorCode(response.body?.string().orEmpty())
                    if (code != null) {
                        ApiLog.dsmError(Allowlist.FS_UPLOAD, code)
                        throw ApiFailure.DsmError(Allowlist.FS_UPLOAD, code)
                    }
                    ApiLog.ok(Allowlist.FS_UPLOAD)
                }
            } catch (e: IOException) {
                ApiLog.transport(Allowlist.FS_UPLOAD, e)
                throw ApiFailure.Transport(Allowlist.FS_UPLOAD, e)
            }
        }

    /** The DSM error code from a `{success:false,error:{code}}` body, or null when it succeeded. */
    private fun errorCode(body: String): Int? {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return null // not a Synology envelope; treat as success (e.g. raw file bytes)
        }
        if (root["success"]?.jsonPrimitive?.content == "true") return null
        return root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: -1
    }

    private companion object {
        const val FILE_NOT_FOUND = 408 // File Station: no such file or directory
    }
}
