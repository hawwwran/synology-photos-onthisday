package com.hawwwran.photosonthisday.api

import okhttp3.HttpUrl

/** The three thumbnail sizes Photos serves; the value is the `size` parameter (research U4). */
enum class ThumbnailSize(val param: String) {
    SMALL("sm"),
    MEDIUM("m"),
    LARGE("xl"),
}

/** What identifies a thumbnail independent of the session: unit, cache key, namespace, size. */
data class ThumbnailRef(
    val space: Space,
    val unitId: Int,
    val cacheKey: String,
    val size: ThumbnailSize,
) {
    /**
     * The disk- and memory-cache key. Deliberately not the URL: the URL carries `_sid`, so a new
     * session would otherwise invalidate every cached thumbnail (plan.md §7, plan 004 acceptance).
     */
    val cacheId: String get() = "$unitId-${size.param}"
}

/**
 * Builds the thumbnail GET. It serves bytes only with the `X-SYNO-TOKEN` header attached
 * separately (research U4); this puts the session in the query as observed to work. The
 * namespace picks the api, because a shared item is fetched from `SYNO.FotoTeam.Thumbnail`.
 */
object ThumbnailUrls {
    fun get(baseUrl: HttpUrl, ref: ThumbnailRef, sid: String): HttpUrl =
        SynologyClient.entryCgi(baseUrl).newBuilder()
            .addQueryParameter("api", Allowlist.thumbnail(ref.space).api)
            .addQueryParameter("method", "get")
            .addQueryParameter("version", Allowlist.thumbnail(ref.space).version.toString())
            .addQueryParameter("id", ref.unitId.toString())
            .addQueryParameter("cache_key", ref.cacheKey)
            .addQueryParameter("type", "unit")
            .addQueryParameter("size", ref.size.param)
            .addQueryParameter("_sid", sid)
            .build()
}
