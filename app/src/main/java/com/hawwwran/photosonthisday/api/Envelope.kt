package com.hawwwran.photosonthisday.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a DSM response body turned out to be. One classifier for the Photos client and the File
 * Station client, so `{success, data}` and `{success: false, error: {code}}` are read one way and
 * a body that is neither is never mistaken for success.
 */
sealed interface Envelope {
    data class Success(val data: JsonElement) : Envelope

    data class Error(val code: Int) : Envelope

    /** Not a `{success, ...}` object: an HTML page, raw file bytes, garbage. [detail] is safe to log. */
    data class NotAnEnvelope(val detail: String) : Envelope
}

/** Never throws: an unparseable body is [Envelope.NotAnEnvelope]. */
fun Json.classifyEnvelope(body: String): Envelope {
    val root = try {
        parseToJsonElement(body)
    } catch (e: SerializationException) {
        return Envelope.NotAnEnvelope("response is not JSON")
    } catch (e: IllegalArgumentException) {
        return Envelope.NotAnEnvelope("response is not JSON")
    }
    if (root !is JsonObject) return Envelope.NotAnEnvelope("response is not a JSON object")
    val success = (root["success"] as? JsonPrimitive)?.booleanOrNull
        ?: return Envelope.NotAnEnvelope("no success flag")
    if (success) return Envelope.Success(root["data"] ?: JsonNull)
    val code = ((root["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
        ?: return Envelope.NotAnEnvelope("error without a code")
    return Envelope.Error(code)
}

/** True when [body] is a DSM envelope at all, whatever it says; a downloaded file is not one. */
fun Json.looksLikeEnvelope(body: String): Boolean = classifyEnvelope(body) !is Envelope.NotAnEnvelope
