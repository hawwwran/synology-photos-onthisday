package com.hawwwran.photosonthisday.data

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.crossfade
import com.hawwwran.photosonthisday.api.ApiCall
import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.ApiLog
import com.hawwwran.photosonthisday.api.acceptsImageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * The app's one Coil loader. It fetches over the API's OkHttp client, so the TLS and no-cleartext
 * rules hold for images too, with two guards around the disk cache:
 *
 * - [ImageResponseGuard] refuses a response that is not an image before Coil writes it to disk.
 *   Photos answers a thumbnail GET with a dead session as HTTP 200 plus a JSON envelope (research
 *   U4); without the guard that envelope was cached under the thumbnail's key and served from disk
 *   on every later request, so a cell stayed grey until Settings > Clear cache.
 * - [FailedDecodePurge] drops the cached entry when a request ends in an error, so an entry
 *   poisoned by an earlier build is fetched again instead of failing forever.
 */
fun buildImageLoader(context: Context, http: OkHttpClient): ImageLoader {
    val guarded = http.newBuilder().addInterceptor(ImageResponseGuard()).build()
    return ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { guarded }))
            add(FailedDecodePurge { SingletonImageLoader.get(context) })
        }
        .crossfade(true)
        .build()
}

/** OkHttp side: a 2xx whose `Content-Type` is not an image type fails as an [IOException]. */
internal class ImageResponseGuard : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 304 || acceptsImageResponse(response.code, response.header("Content-Type"))) {
            return response
        }
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        response.close()
        // The URL is the app's own GET, with the session in a cookie, so its api/method name is safe to log.
        val url = chain.request().url
        val call = ApiCall(
            api = url.queryParameter("api") ?: "?",
            method = url.queryParameter("method") ?: "?",
            version = url.queryParameter("version")?.toIntOrNull() ?: 0,
        )
        ApiLog.failure(ApiFailure.Malformed(call, "HTTP ${response.code}, type '$type'"))
        throw IOException("not an image: HTTP ${response.code}, type '$type'")
    }
}

/** Coil side: an [ErrorResult] evicts the request's disk and memory entries, if any. */
internal class FailedDecodePurge(private val loader: () -> ImageLoader) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result is ErrorResult) {
            val imageLoader = loader()
            chain.request.memoryCacheKey?.let { imageLoader.memoryCache?.remove(MemoryCache.Key(it)) }
            chain.request.diskCacheKey?.let { key ->
                withContext(Dispatchers.IO) { imageLoader.diskCache?.remove(key) }
            }
        }
        return result
    }
}
