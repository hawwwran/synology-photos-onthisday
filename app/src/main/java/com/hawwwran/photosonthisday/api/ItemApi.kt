package com.hawwwran.photosonthisday.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl

/**
 * `Browse.Item`. `count` cross-checks the histogram (plan 003); `list` fetches one day by
 * `start_time`/`end_time` (decision 005, plan 004). Both are reads on the allowlist.
 */
class ItemApi(
    private val client: SynologyClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun count(baseUrl: HttpUrl, space: Space, credentials: SessionCredentials): Int {
        val data = client.callObject(baseUrl, Allowlist.itemCount(space), credentials = credentials)
        return (data["count"] as? JsonPrimitive)?.intOrNull
            ?: throw ApiFailure.Malformed(Allowlist.itemCount(space), "count response has no count")
    }

    /**
     * Items whose taken time falls in [range], newest first, paged by [offset]/[limit] so a day
     * larger than one page can be read in slices. The thumbnail and resolution are requested so
     * the grid needs no follow-up call.
     */
    suspend fun list(
        baseUrl: HttpUrl,
        space: Space,
        range: LongRange,
        offset: Int,
        limit: Int,
        credentials: SessionCredentials,
    ): List<PhotoItem> {
        val call = Allowlist.itemList(space)
        val params = mapOf(
            "offset" to offset.toString(),
            "limit" to limit.toString(),
            "sort_by" to "takentime",
            "sort_direction" to "desc",
            "start_time" to range.first.toString(),
            "end_time" to range.last.toString(),
            "additional" to """["thumbnail","resolution"]""",
        )
        val data = client.call(baseUrl, call, params, credentials)
        return decode(call, data).list.mapNotNull { it.toPhotoItem(space) }
    }

    private fun decode(call: ApiCall, data: JsonElement): ItemListData = try {
        json.decodeFromJsonElement(ItemListData.serializer(), data)
    } catch (e: SerializationException) {
        throw ApiFailure.Malformed(call, "item response has an unexpected shape")
    } catch (e: IllegalArgumentException) {
        throw ApiFailure.Malformed(call, "item response has an unexpected shape")
    }
}

/** Null when an item carries no thumbnail: it cannot be shown in the grid, so it is dropped. */
private fun ItemDto.toPhotoItem(space: Space): PhotoItem? {
    val thumb = additional?.thumbnail ?: return null
    return PhotoItem(
        space = space,
        id = id,
        unitId = thumb.unit_id,
        cacheKey = thumb.cache_key,
        takenTimeSeconds = time,
        isVideo = type == "video",
        width = additional.resolution?.width ?: 0,
        height = additional.resolution?.height ?: 0,
        filename = filename,
        filesize = filesize,
        folderId = folder_id,
    )
}
