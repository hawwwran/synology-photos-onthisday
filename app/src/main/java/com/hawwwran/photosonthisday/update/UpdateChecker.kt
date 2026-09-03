package com.hawwwran.photosonthisday.update

import com.hawwwran.photosonthisday.api.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
    /** Derived from the app's client, so its TLS and no-retry rules hold; GitHub's API does not redirect. */
    appClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val json: Json = AppJson,
) : UpdateChecking {
    private val client: OkHttpClient = appClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun check(force: Boolean): CheckOutcome {
        val cached = withContext(Dispatchers.IO) { readCache() }
        if (!force && cached != null && (now() - cached.fetchedAt) in 0 until CACHE_TTL_MS) {
            return outcome(cached, stale = false)
        }
        return executeAndProcess(buildRequest(cached?.lastModified), cached)
    }

    private suspend fun executeAndProcess(request: Request, cached: CachedRelease?): CheckOutcome =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(fallback(cached))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    val result = try {
                        response.use { processResponse(it, cached) }
                    } catch (_: IOException) {
                        fallback(cached)
                    } catch (e: CancellationException) {
                        if (cont.isActive) cont.cancel(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

    /** No usable answer: replay the cache as stale, or admit nothing is known. */
    private fun fallback(cached: CachedRelease?): CheckOutcome =
        cached?.let { outcome(it, stale = true) } ?: CheckOutcome.Unreachable

    private fun processResponse(resp: Response, cached: CachedRelease?): CheckOutcome = when (resp.code) {
        304 -> cached?.let {
            writeCache(it.copy(fetchedAt = now()))
            outcome(it, stale = false)
        } ?: CheckOutcome.Unreachable
        200 -> handleOk(resp, cached)
        else -> fallback(cached)
    }

    private fun handleOk(resp: Response, cached: CachedRelease?): CheckOutcome {
        val body = resp.body.string()
        if (body.isEmpty()) return fallback(cached)
        // A page with no matching release is not a reason to drop a good cache: leave it and
        // report that there is nothing to update to.
        val parsed = parseRelease(body) ?: return CheckOutcome.NoRelease
        val fresh = CachedRelease(now(), resp.header("Last-Modified") ?: "", parsed)
        writeCache(fresh)
        return outcome(fresh, stale = false)
    }

    private fun buildRequest(lastModified: String?): Request {
        val builder = Request.Builder()
            .url("$baseUrl/repos/$REPO/releases?per_page=$PER_PAGE")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
        if (!lastModified.isNullOrEmpty()) builder.header("If-Modified-Since", lastModified)
        return builder.build()
    }

    private fun outcome(cached: CachedRelease, stale: Boolean): CheckOutcome {
        val r = cached.release
        if (!r.tagName.startsWith(TAG_PREFIX) || r.apkUrl.isEmpty()) return CheckOutcome.NoRelease
        val latest = r.tagName.removePrefix(TAG_PREFIX)
        return CheckOutcome.Found(
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latest,
                apkUrl = r.apkUrl,
                apkSize = r.apkSize,
                releaseNotes = r.body,
                isNewer = isNewerVersion(latest, currentVersion),
                stale = stale,
            ),
        )
    }

    /** The newest non-draft, non-prerelease `v*` release that carries an `.apk` asset. */
    private fun parseRelease(body: String): ParsedRelease? {
        val releases = try {
            json.parseToJsonElement(body) as? JsonArray ?: return null
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        for (element in releases) {
            val release = element as? JsonObject ?: continue
            if (release.bool("draft") || release.bool("prerelease")) continue
            val tag = release.string("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) continue
            val apk = (release["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("name").endsWith(".apk", ignoreCase = true) && it.string("browser_download_url").isNotEmpty() }
                ?: continue
            return ParsedRelease(tag, apk.string("browser_download_url"), apk.long("size"), release.string("body"))
        }
        return null
    }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun readCache(): CachedRelease? {
        if (!cacheFile.exists()) return null
        return try {
            json.decodeFromString(CachedRelease.serializer(), cacheFile.readText())
        } catch (_: SerializationException) {
            null // a cache from an older build or a torn write: the next check re-fetches
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun writeCache(cached: CachedRelease) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.absolutePath + ".tmp")
            tmp.writeText(json.encodeToString(CachedRelease.serializer(), cached))
            tmp.renameTo(cacheFile)
        } catch (_: IOException) {
            // Best-effort: a failed write just means the next check re-fetches.
        }
    }

    @Serializable
    private data class CachedRelease(val fetchedAt: Long, val lastModified: String = "", val release: ParsedRelease)

    @Serializable
    private data class ParsedRelease(val tagName: String, val apkUrl: String, val apkSize: Long = 0, val body: String = "")

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
