package com.hawwwran.photosonthisday.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl

/**
 * `Browse.Item`. Plan 003 needs only `count`, to cross-check the flattened histogram against
 * what DSM says the namespace holds; `list` (by `start_time`/`end_time`) arrives in plan 004.
 */
class ItemApi(private val client: SynologyClient) {
    suspend fun count(baseUrl: HttpUrl, space: Space, credentials: SessionCredentials): Int {
        val data = client.call(baseUrl, Allowlist.itemCount(space), credentials = credentials)
        return data.jsonObject["count"]?.jsonPrimitive?.intOrNull
            ?: throw ApiFailure.Malformed(Allowlist.itemCount(space), "count response has no count")
    }
}
