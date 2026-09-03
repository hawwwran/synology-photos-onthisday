package com.hawwwran.photosonthisday.ui.day

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.api.DownloadUrls
import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailSize
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.czech
import com.hawwwran.photosonthisday.likes.likeKey
import com.hawwwran.photosonthisday.ui.formatBytes
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SEEK_STEP_MS = 15_000L

/**
 * Fullscreen pager over the whole day, every year in one sequence. A photo zooms; a video plays
 * with the classic controls, hidden until the video is tapped. The top bar (year and time, back,
 * save) shows in portrait, and in landscape the app goes immersive on a video: the top bar and
 * the system bars hide with the controls, leaving only the video, until it is tapped.
 */
@Composable
fun ViewerScreen(
    items: List<PhotoItem>,
    startIndex: Int,
    auth: ThumbnailAuth,
    /** The app's client, so video streams under the same TLS, timeout and no-redirect rules as every other call. */
    http: OkHttpClient,
    likedKeys: Set<String>,
    onToggleLike: (PhotoItem) -> Unit,
    onBack: () -> Unit,
    onSave: (PhotoItem) -> Unit,
    onShare: (PhotoItem) -> Unit,
    resolvePath: suspend (PhotoItem) -> String?,
    saving: Boolean,
    sharing: Boolean,
) {
    if (items.isEmpty()) {
        onBack()
        return
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by remember { mutableStateOf(false) }
    var infoShown by remember { mutableStateOf(false) }
    val current = items[pagerState.currentPage]
    LaunchedEffect(pagerState.currentPage) { controlsVisible = false }

    if (infoShown) InfoDialog(current, resolvePath, onDismiss = { infoShown = false })

    val immersive = landscape && current.isVideo && !controlsVisible
    ImmersiveSystemBars(immersive)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val entry = items[page]
            if (entry.isVideo) {
                VideoPage(
                    item = entry,
                    auth = auth,
                    http = http,
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
                    text = fullDate(current.takenTimeSeconds),
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = { infoShown = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.viewer_info), tint = Color.White)
                }
                val liked = likedKeys.contains(likeKey(current.space, current.unitId))
                IconButton(onClick = { onToggleLike(current) }) {
                    Icon(
                        imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (liked) R.string.viewer_unlike else R.string.viewer_like),
                        tint = if (liked) MaterialTheme.colorScheme.tertiary else Color.White,
                    )
                }
                if (sharing) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp))
                } else {
                    IconButton(onClick = { onShare(current) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.viewer_share), tint = Color.White)
                    }
                }
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
 * the left half 15 s back. Only the page on screen plays, and only while the activity is started:
 * Home or the power button pauses, coming back resumes the active page.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(
    item: PhotoItem,
    auth: ThumbnailAuth,
    http: OkHttpClient,
    isActive: Boolean,
    onControlsVisibilityChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .build()
    }
    var controlsShown by remember(item.id) { mutableStateOf(false) }
    val playerView = remember(item.id) {
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

    DisposableEffect(item.id) {
        // `_sid` rides in the URL here, as for the save: the cookie form was verified only for
        // thumbnails (research, "Update, second run"). OkHttp, not HttpURLConnection, so the app
        // client's no-redirect and timeout policy applies to a URL that carries the session.
        val url = DownloadUrls.original(auth.baseUrl, item.space, item.unitId, auth.sid).toString()
        val headers = buildMap<String, String> { auth.token?.let { put(SynologyClient.SYNO_TOKEN_HEADER, it) } }
        val dataSource = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
        val source = ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        onDispose {
            playerView.player = null
            player.release()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isActive, started) { player.playWhenReady = isActive && started }

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
                    .pointerInput(item.id) {
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

private const val MIN_SCALE = 1f     // fit; smaller is allowed only as rubber-band during a pinch
private const val RUBBER_MIN = 0.7f
private const val MAX_SCALE = 5f

/**
 * A photo that pinch-zooms. At fit (scale 1) a single-finger drag is left to the pager, so the
 * day's photos swipe like the videos do; only when zoomed in does the drag pan the image
 * (`canPan`). A pinch may shrink below fit for feedback, and snaps back to fit when the fingers
 * lift, so a photo is never left smaller than fit.
 */
@Composable
private fun ZoomableImage(item: PhotoItem, auth: ThumbnailAuth) {
    val context = LocalContext.current
    var scale by remember(item.id) { mutableStateOf(MIN_SCALE) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(RUBBER_MIN, MAX_SCALE)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    // When the gesture ends below fit, settle back to exactly fit, centred.
    LaunchedEffect(state) {
        snapshotFlow { state.isTransformInProgress }.collect { inProgress ->
            if (!inProgress && scale < MIN_SCALE) {
                scale = MIN_SCALE
                offset = Offset.Zero
            }
        }
    }

    val ref = ThumbnailRef(item.space, item.unitId, item.cacheKey, ThumbnailSize.LARGE)
    val request = remember(ref, auth) { thumbnailRequest(context, ref, auth) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The full taken date and time, Czech, e.g. "2. září 2026 · 18:42". `time` is the wall clock
 * stored as if UTC (research U7), so it is read back as UTC unchanged. The date is shown, not
 * just the year, so a person paging across years does not lose track of the day.
 */
private fun fullDate(seconds: Long): String {
    val at = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC)
    val monthDay = MonthDay(at.monthValue, at.dayOfMonth)
    return "${monthDay.czech()} ${at.year} · ${at.format(TIME_FORMAT)}"
}

/**
 * The metadata the app already holds for an item: taken date and time, kind, pixel size and
 * megapixels, byte size and file name. No EXIF (camera, GPS) is fetched; the web API's item
 * list does not carry it (research). The file name is shown here but, per the read client's
 * rule, never logged.
 */
@Composable
private fun InfoDialog(item: PhotoItem, resolvePath: suspend (PhotoItem) -> String?, onDismiss: () -> Unit) {
    // null = still resolving; "" = resolved to nothing (row omitted); else the folder path.
    var path by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) { path = resolvePath(item) ?: "" }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info_close)) } },
        title = { Text(stringResource(R.string.info_title)) },
        text = {
            Column {
                InfoRow(stringResource(R.string.info_taken), fullDate(item.takenTimeSeconds))
                InfoRow(
                    stringResource(R.string.info_kind),
                    stringResource(if (item.isVideo) R.string.info_kind_video else R.string.info_kind_photo),
                )
                if (item.width > 0 && item.height > 0) {
                    InfoRow(stringResource(R.string.info_resolution), resolutionText(item.width, item.height))
                }
                if (item.filesize > 0) InfoRow(stringResource(R.string.info_size), formatBytes(item.filesize))
                if (item.filename.isNotBlank()) InfoRow(stringResource(R.string.info_filename), item.filename)
                val folderPath = path
                if (folderPath == null) {
                    InfoRow(stringResource(R.string.info_location), stringResource(R.string.info_location_loading))
                } else if (folderPath.isNotBlank()) {
                    InfoRow(stringResource(R.string.info_location), folderPath)
                }
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** e.g. "4032 × 3024 (12,2 Mpx)". Megapixels use the device locale's decimal separator. */
private fun resolutionText(width: Int, height: Int): String {
    val megapixels = width.toLong() * height.toLong() / 1_000_000.0
    return "$width × $height (${String.format(Locale.getDefault(), "%.1f", megapixels)} Mpx)"
}
