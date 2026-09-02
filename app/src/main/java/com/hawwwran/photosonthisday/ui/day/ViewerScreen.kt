package com.hawwwran.photosonthisday.ui.day

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.api.DownloadUrls
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailSize
import com.hawwwran.photosonthisday.api.ThumbnailUrls
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val SEEK_STEP_MS = 15_000L

/**
 * Fullscreen pager over the whole day, every year in one sequence. A photo zooms; a video plays
 * with the classic controls, hidden until the video is tapped. The top bar (year and time, back,
 * save) shows in portrait, and in landscape the app goes immersive on a video: the top bar and
 * the system bars hide with the controls, leaving only the video, until it is tapped.
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
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by remember { mutableStateOf(false) }
    val current = items[pagerState.currentPage]
    LaunchedEffect(pagerState.currentPage) { controlsVisible = false }

    val immersive = landscape && current.item.isVideo && !controlsVisible
    ImmersiveSystemBars(immersive)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val entry = items[page]
            if (entry.item.isVideo) {
                VideoPage(
                    entry = entry,
                    auth = auth,
                    isActive = page == pagerState.currentPage,
                    onControlsVisibilityChange = { visible -> if (page == pagerState.currentPage) controlsVisible = visible },
                )
            } else {
                ZoomableImage(entry, auth)
            }
        }

        if (!immersive) {
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
}

/** Hides or restores the system bars for immersive video, and always restores them on leaving. */
@Composable
private fun ImmersiveSystemBars(immersive: Boolean) {
    val view = LocalView.current
    LaunchedEffect(immersive) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Plays a video, streamed from the download endpoint (session in the query, token in the header).
 * The controls start hidden so they never sit over the video unasked: a single tap shows them
 * (they auto-hide after a few seconds), a double-tap on the right half skips 15 s forward and on
 * the left half 15 s back. Only the page on screen plays.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(
    entry: ViewerItem,
    auth: ThumbnailAuth,
    isActive: Boolean,
    onControlsVisibilityChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(entry.item.id) {
        ExoPlayer.Builder(context)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .build()
    }
    var controlsShown by remember(entry.item.id) { mutableStateOf(false) }
    val playerView = remember(entry.item.id) {
        PlayerView(context).apply {
            this.player = player
            useController = true
            controllerAutoShow = false        // hidden on open, so it never covers the video unasked
            controllerShowTimeoutMs = 3_000
            hideController()
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    val shown = visibility == View.VISIBLE
                    controlsShown = shown
                    onControlsVisibilityChange(shown)
                },
            )
        }
    }

    DisposableEffect(entry.item.id) {
        val url = DownloadUrls.original(auth.baseUrl, entry.item.space, entry.item.unitId, auth.sid).toString()
        val headers = buildMap<String, String> { auth.token?.let { put(SynologyClient.SYNO_TOKEN_HEADER, it) } }
        val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val source = ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        onDispose {
            playerView.player = null
            player.release()
        }
    }
    LaunchedEffect(isActive) { player.playWhenReady = isActive }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

        // While the controls are hidden, this layer owns the taps: a single tap reveals the
        // controls, a double-tap seeks by half. When the controls are shown, the layer steps
        // aside so the seek bar and buttons receive touches directly (a tap on the video then
        // hides the controls again, through the PlayerView's own handling).
        if (!controlsShown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(entry.item.id) {
                        detectTapGestures(
                            onTap = { playerView.showController() },
                            onDoubleTap = { offset ->
                                val forward = offset.x > size.width / 2f
                                val target = player.currentPosition + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
                                player.seekTo(target.coerceIn(0L, player.duration.coerceAtLeast(0L)))
                            },
                        )
                    },
            )
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
