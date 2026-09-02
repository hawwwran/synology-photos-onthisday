package com.hawwwran.photosonthisday.ui.day

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailUrls
import okhttp3.HttpUrl

/** What the grid needs to fetch a thumbnail: where the NAS is and what proves this session. */
data class ThumbnailAuth(val baseUrl: HttpUrl, val sid: String, val token: String?)

/**
 * One grid cell. The disk- and memory-cache key is the unit and size, not the URL, so signing
 * out and back in reuses the cached image instead of downloading it again. The `X-SYNO-TOKEN`
 * header is required or the GET returns a JSON error (research U4); a decode failure of such a
 * body lands on the error state rather than being shown.
 */
@Composable
fun Thumbnail(ref: ThumbnailRef, auth: ThumbnailAuth, isVideo: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(ref, auth) {
        ImageRequest.Builder(context)
            .data(ThumbnailUrls.get(auth.baseUrl, ref, auth.sid).toString())
            .apply {
                if (!auth.token.isNullOrEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().set(SynologyClient.SYNO_TOKEN_HEADER, auth.token).build())
                }
            }
            .memoryCacheKey(ref.cacheId)
            .diskCacheKey(ref.cacheId)
            .crossfade(true)
            .build()
    }
    Box(modifier) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(28.dp).padding(2.dp),
            )
        }
    }
}
