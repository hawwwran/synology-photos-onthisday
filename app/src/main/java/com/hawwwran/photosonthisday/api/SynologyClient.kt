package com.hawwwran.photosonthisday.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val json: Json = Json { ignoreUnknownKeys = true },
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
                        throw malformed(call, "HTTP ${response.code}")
                    }
                    response.body.string()
                }
            } catch (e: IOException) {
                ApiLog.transport(call, e)
                throw ApiFailure.Transport(call, e)
            }
        }
        return decodeEnvelope(call, body)
    }

    private fun decodeEnvelope(call: ApiCall, body: String): JsonElement {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw malformed(call, "response is not JSON")
        } catch (e: IllegalArgumentException) {
            throw malformed(call, "response is not a JSON object")
        }
        val success = root["success"]?.jsonPrimitive?.booleanOrNull
            ?: throw malformed(call, "no success flag")
        if (success) {
            ApiLog.ok(call)
            return root["data"] ?: JsonNull
        }
        val code = root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull
            ?: throw malformed(call, "error without a code")
        ApiLog.dsmError(call, code)
        if (code in ApiFailure.SESSION_GONE_CODES) throw ApiFailure.SessionExpired(call, code)
        throw ApiFailure.DsmError(call, code)
    }

    private fun malformed(call: ApiCall, detail: String): ApiFailure.Malformed {
        ApiLog.malformed(call, detail)
        return ApiFailure.Malformed(call, detail)
    }

    companion object {
        const val SYNO_TOKEN_HEADER = "X-SYNO-TOKEN"

        /** Thumbnails (plan 004) build a GET on the same path, so the join lives here once. */
        fun entryCgi(baseUrl: HttpUrl): HttpUrl =
            baseUrl.newBuilder().addPathSegments("webapi/entry.cgi").build()
    }
}
