package com.hawwwran.photosonthisday.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What a signed-in call carries: the session id in the body, the token in a header. */
data class SessionCredentials(val sid: String, val synotoken: String?)

/**
 * The `entry.cgi` call layer. Every Photos call is a form-encoded POST with the api, method
 * and version in the body, answered by `{success, data}` or `{success: false, error: {code}}`
 * (`documents/research/photos-web-api.md`, "Call shape").
 *
 * Two invariants live here. The triple is checked against the [Allowlist] before a request
 * object exists, and the response body is decoded and then forgotten: the only things that
 * leave this class are `data`, or an [ApiFailure] naming the call and a code.
 *
 * The base URL arrives as an [HttpUrl] already validated by [parseBaseUrl]; this class does not
 * re-check the scheme so that unit tests can point it at a plain MockWebServer.
 */
class SynologyClient(
    private val http: OkHttpClient,
    private val json: Json = AppJson,
) {

    /**
     * POST [call] with [params]. [credentials] is null only for `SYNO.API.Info` and the login
     * itself. Returns `data`, which is [JsonNull] when DSM sends none (logout does that).
     */
    suspend fun call(
        baseUrl: HttpUrl,
        call: ApiCall,
        params: Map<String, String> = emptyMap(),
        credentials: SessionCredentials? = null,
    ): JsonElement {
        Allowlist.require(call)
        val form = FormBody.Builder().apply {
            add("api", call.api)
            add("method", call.method)
            add("version", call.version.toString())
            params.forEach { (key, value) -> add(key, value) }
            credentials?.let { add("_sid", it.sid) }
        }.build()
        val request = Request.Builder()
            .url(entryCgi(baseUrl))
            .post(form)
            .apply { credentials?.synotoken?.let { header(SYNO_TOKEN_HEADER, it) } }
            .build()

        val body = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw malformed(call, MalformedDetail.http(response.code))
                    }
                    response.body.string()
                }
            } catch (e: IOException) {
                throw ApiFailure.Transport(call, e).also(ApiLog::failure)
            }
        }
        return decodeEnvelope(call, body)
    }

    /**
     * [call], for the methods whose `data` is an object. A success envelope with no `data`, or
     * with `data` of another shape, is [ApiFailure.Malformed] here rather than a raw cast
     * exception at the caller, so nothing but an [ApiFailure] leaves the api layer.
     */
    suspend fun callObject(
        baseUrl: HttpUrl,
        call: ApiCall,
        params: Map<String, String> = emptyMap(),
        credentials: SessionCredentials? = null,
    ): JsonObject = this.call(baseUrl, call, params, credentials) as? JsonObject
        ?: throw malformed(call, "data is not an object")

    private fun decodeEnvelope(call: ApiCall, body: String): JsonElement =
        when (val envelope = json.classifyEnvelope(body)) {
            is Envelope.Success -> {
                ApiLog.ok(call)
                envelope.data
            }
            is Envelope.Error -> throw ApiFailure.fromDsmCode(call, envelope.code).also(ApiLog::failure)
            is Envelope.NotAnEnvelope -> throw malformed(call, envelope.detail)
        }

    private fun malformed(call: ApiCall, detail: String): ApiFailure.Malformed =
        ApiFailure.Malformed(call, detail).also(ApiLog::failure)

    companion object {
        const val SYNO_TOKEN_HEADER = "X-SYNO-TOKEN"

        /**
         * DSM reads the session from a cookie named `id`, which keeps the session id out of the
         * URL and therefore out of the reverse proxy's access log (research U4). Thumbnails and
         * the download use this instead of `_sid` in the query.
         */
        fun sessionCookie(sid: String): String = "id=$sid"

        /** Thumbnails (plan 004) build a GET on the same path, so the join lives here once. */
        fun entryCgi(baseUrl: HttpUrl): HttpUrl =
            baseUrl.newBuilder().addPathSegments("webapi/entry.cgi").build()
    }
}
