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
     * The disk- and memory-cache key. Deliberately not the URL, which names the NAS: the key must
     * survive a sign-out and a new session (plan.md §7, plan 004 acceptance). `cacheKey` is in it
     * because Photos changes it when a photo is edited, and the old rendition must not be shown
     * forever. Unit ids are unique across the two namespaces (one `unit` table in the library).
     */
    val cacheId: String get() = "$unitId-$cacheKey-${size.param}"
}

/**
 * Whether a thumbnail GET may be decoded and cached. Photos answers a request with a missing or
 * stale token as HTTP 200 with a JSON error envelope (research U4); an image loader that keyed
 * on the status alone would cache that envelope as the picture and serve it from disk forever.
 * Pure, so the rule is tested without a loader.
 */
fun acceptsImageResponse(statusCode: Int, contentType: String?): Boolean =
    statusCode in 200..299 && contentType.orEmpty().substringBefore(';').trim().startsWith("image/", ignoreCase = true)

/**
 * Builds the thumbnail GET. It serves bytes only with the `X-SYNO-TOKEN` header attached and the
 * session in a `Cookie: id=<sid>` header (research U4); nothing secret goes in the URL, so the
 * reverse proxy's access log never sees the session id. The namespace picks the api, because a
 * shared item is fetched from `SYNO.FotoTeam.Thumbnail`.
 */
object ThumbnailUrls {
    fun get(baseUrl: HttpUrl, ref: ThumbnailRef): HttpUrl {
        Allowlist.require(Allowlist.thumbnail(ref.space))
        return SynologyClient.entryCgi(baseUrl).newBuilder()
            .addQueryParameter("api", Allowlist.thumbnail(ref.space).api)
            .addQueryParameter("method", "get")
            .addQueryParameter("version", Allowlist.thumbnail(ref.space).version.toString())
            .addQueryParameter("id", ref.unitId.toString())
            .addQueryParameter("cache_key", ref.cacheKey)
            .addQueryParameter("type", "unit")
            .addQueryParameter("size", ref.size.param)
            .build()
    }
}

/**
 * Builds the download GET for the original file. `SYNO.Foto.Download` `download` v2 with
 * `unit_id=[<id>]` returns the original bytes (research, download probe). The probe verified the
 * session in the query with the token header; the cookie form was verified only for the
 * thumbnail, so the download uses `_sid` in the query, which is what was observed to work.
 */
object DownloadUrls {
    fun original(baseUrl: HttpUrl, space: Space, unitId: Int, sid: String): HttpUrl {
        Allowlist.require(Allowlist.download(space))
        return SynologyClient.entryCgi(baseUrl).newBuilder()
            .addQueryParameter("api", Allowlist.download(space).api)
            .addQueryParameter("method", "download")
            .addQueryParameter("version", Allowlist.download(space).version.toString())
            .addQueryParameter("unit_id", "[$unitId]")
            .addQueryParameter("_sid", sid)
            .build()
    }
}
