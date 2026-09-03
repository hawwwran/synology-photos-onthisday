package com.hawwwran.photosonthisday.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Polls GitHub Releases for `v*` tags newer than the running app's versionName, and finds the
 * `.apk` asset to install. The response is cached on disk for 24 h and re-checks within that
 * window send `If-Modified-Since`, so they cost zero bytes when nothing changed.
 *
 * Tight timeouts (3 s connect / 5 s read / 8 s overall) and coroutine-cancellation cooperation
 * keep `check()` from ever blocking longer than ~8 s, and abort at once when the activity
 * backgrounds. Pure of UI so it can be unit-tested with MockWebServer.
 *
 * No debug-build gate: this app's debug builds co-sign with the release key (see
 * `app/build.gradle.kts`), so a release APK from GitHub installs over a debug install.
 */
class UpdateChecker(
    private val cacheFile: File,
    private val currentVersion: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    httpClient: OkHttpClient? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun check(force: Boolean = false): UpdateInfo? {
        val cached = withContext(Dispatchers.IO) { readCache() }
        if (!force && cached != null && (now() - cached.fetchedAt) in 0 until CACHE_TTL_MS) {
            return buildInfo(cached, stale = false)
        }
        return executeAndProcess(buildRequest(cached?.lastModified), cached)
    }

    private suspend fun executeAndProcess(request: Request, cached: CachedRelease?): UpdateInfo? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(cached?.let { buildInfo(it, stale = true) })
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    val info = try {
                        response.use { processResponse(it, cached) }
                    } catch (_: IOException) {
                        cached?.let { buildInfo(it, stale = true) }
                    } catch (e: CancellationException) {
                        if (cont.isActive) cont.cancel(e)
                        return
                    }
                    if (cont.isActive) cont.resume(info)
                }
            })
        }

    private fun processResponse(resp: Response, cached: CachedRelease?): UpdateInfo? = when (resp.code) {
        304 -> cached?.let {
            writeCache(it.copy(fetchedAt = now()))
            buildInfo(it, stale = false)
        }
        200 -> handleOk(resp, cached)
        else -> cached?.let { buildInfo(it, stale = true) }
    }

    private fun handleOk(resp: Response, cached: CachedRelease?): UpdateInfo? {
        val body = resp.body?.string()
        if (body.isNullOrEmpty()) return cached?.let { buildInfo(it, stale = true) }
        // A page with no matching release is not a reason to drop a good cache: leave it and
        // return null so no update is surfaced either way.
        val parsed = parseRelease(body) ?: return null
        val fresh = CachedRelease(now(), resp.header("Last-Modified") ?: "", parsed)
        writeCache(fresh)
        return buildInfo(fresh, stale = false)
    }

    private fun buildRequest(lastModified: String?): Request {
        val builder = Request.Builder()
            .url("$baseUrl/repos/$REPO/releases?per_page=$PER_PAGE")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
        if (!lastModified.isNullOrEmpty()) builder.header("If-Modified-Since", lastModified)
        return builder.build()
    }

    private fun buildInfo(cached: CachedRelease, stale: Boolean): UpdateInfo? {
        val r = cached.release
        if (!r.tagName.startsWith(TAG_PREFIX) || r.apkUrl.isEmpty()) return null
        val latest = r.tagName.removePrefix(TAG_PREFIX)
        return UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latest,
            releaseUrl = r.htmlUrl,
            apkUrl = r.apkUrl,
            releaseNotes = r.body,
            isNewer = isNewerVersion(latest, currentVersion),
            stale = stale,
        )
    }

    private fun parseRelease(body: String): ParsedRelease? = try {
        val arr = JSONArray(body)
        var found: ParsedRelease? = null
        var i = 0
        while (i < arr.length() && found == null) {
            val item = arr.optJSONObject(i)
            if (item != null && !item.optBoolean("draft", false) && !item.optBoolean("prerelease", false)) {
                val tag = item.optString("tag_name", "")
                val apkUrl = extractApkUrl(item.optJSONArray("assets"))
                if (tag.startsWith(TAG_PREFIX) && apkUrl != null) {
                    found = ParsedRelease(tag, item.optString("html_url", ""), apkUrl, item.optString("body", ""))
                }
            }
            i++
        }
        found
    } catch (_: Exception) {
        null
    }

    private fun extractApkUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                val url = asset.optString("browser_download_url", "")
                if (url.isNotEmpty()) return url
            }
        }
        return null
    }

    private fun readCache(): CachedRelease? {
        if (!cacheFile.exists()) return null
        return try {
            val obj = org.json.JSONObject(cacheFile.readText())
            val release = obj.optJSONObject("release") ?: return null
            CachedRelease(
                fetchedAt = obj.optLong("fetched_at", 0L),
                lastModified = obj.optString("last_modified", ""),
                release = ParsedRelease(
                    tagName = release.optString("tag_name", ""),
                    htmlUrl = release.optString("html_url", ""),
                    apkUrl = release.optString("apk_url", ""),
                    body = release.optString("body", ""),
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(cached: CachedRelease) {
        try {
            cacheFile.parentFile?.mkdirs()
            val release = org.json.JSONObject()
                .put("tag_name", cached.release.tagName)
                .put("html_url", cached.release.htmlUrl)
                .put("apk_url", cached.release.apkUrl)
                .put("body", cached.release.body)
            val payload = org.json.JSONObject()
                .put("fetched_at", cached.fetchedAt)
                .put("last_modified", cached.lastModified)
                .put("release", release)
            val tmp = File(cacheFile.absolutePath + ".tmp")
            tmp.writeText(payload.toString())
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
            // Best-effort: a failed write just means the next check re-fetches.
        }
    }

    private data class CachedRelease(val fetchedAt: Long, val lastModified: String, val release: ParsedRelease)
    private data class ParsedRelease(val tagName: String, val htmlUrl: String, val apkUrl: String, val body: String)

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.github.com"
        private const val REPO = "hawwwran/synology-photos-onthisday"
        private const val PER_PAGE = 30
        internal const val TAG_PREFIX = "v"
        private const val USER_AGENT = "onthisday-android-updater"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        /**
         * Naive dotted-int compare. Returns false on any non-int component, so an unrecognised
         * tag is treated as NOT newer rather than crashing on `toInt()`.
         */
        fun isNewerVersion(latest: String, current: String): Boolean {
            val lp = parseDottedInts(latest) ?: return false
            val cp = parseDottedInts(current) ?: return false
            val n = maxOf(lp.size, cp.size)
            for (i in 0 until n) {
                val a = lp.getOrElse(i) { 0 }
                val b = cp.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun parseDottedInts(s: String): List<Int>? {
            val parts = mutableListOf<Int>()
            for (piece in s.split('.')) parts.add(piece.toIntOrNull() ?: return null)
            return parts
        }
    }
}
