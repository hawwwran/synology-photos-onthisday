package com.hawwwran.photosonthisday.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailSize
import com.hawwwran.photosonthisday.api.ThumbnailUrls
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Fullscreen pager over the whole day, every year in one sequence. Shows the large rendition,
 * which pinch-zooms; the year and the taken time overlay the top. Saving writes the shown image
 * to the gallery (see [com.hawwwran.photosonthisday.data.ImageSaver] on why it is the large
 * rendition, not the byte-original).
 */
@Composable
fun ViewerScreen(
    items: List<ViewerItem>,
    startIndex: Int,
    auth: ThumbnailAuth,
    onBack: () -> Unit,
    onSave: (ViewerItem) -> Unit,
    saving: Boolean,
) {
    if (items.isEmpty()) {
        onBack()
        return
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(items[page], auth)
        }

        val current = items[pagerState.currentPage]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.viewer_back), tint = Color.White)
            }
            Text(
                text = "${current.year} · ${takenTime(current.item.takenTimeSeconds)}",
                color = Color.White,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (saving) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp))
            } else {
                IconButton(onClick = { onSave(current) }) {
                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.viewer_save), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(entry: ViewerItem, auth: ThumbnailAuth) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val ref = ThumbnailRef(entry.item.space, entry.item.unitId, entry.item.cacheKey, ThumbnailSize.LARGE)
    val request = remember(ref, auth) {
        ImageRequest.Builder(context)
            .data(ThumbnailUrls.get(auth.baseUrl, ref).toString())
            .httpHeaders(auth.networkHeaders())
            .memoryCacheKey(ref.cacheId)
            .diskCacheKey(ref.cacheId)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
        )
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** `time` is the wall clock stored as if UTC (research U7), so read it back as UTC unchanged. */
private fun takenTime(seconds: Long): String =
    Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).format(TIME_FORMAT)
