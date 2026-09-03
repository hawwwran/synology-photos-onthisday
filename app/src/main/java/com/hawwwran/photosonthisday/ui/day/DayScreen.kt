package com.hawwwran.photosonthisday.ui.day

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import com.hawwwran.photosonthisday.likes.likeKey
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailSize
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.czech
import java.time.Instant
import java.time.ZoneOffset

/**
 * The chosen day: a title naming it (tap to jump to any day), arrows to the previous and next
 * day, and a year-by-year grid, newest year first. One `LazyVerticalGrid` carries every year,
 * with a full-width header per year, so a day of hundreds of photos scrolls as one list and
 * thumbnails load only as their cells appear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    viewModel: DayViewModel,
    auth: ThumbnailAuth,
    likedKeys: Set<String>,
    gridState: LazyGridState,
    onOpenPhoto: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    onDownloadSelected: (List<com.hawwwran.photosonthisday.api.PhotoItem>) -> Unit,
    onShareSelected: (List<com.hawwwran.photosonthisday.api.PhotoItem>) -> Unit,
) {
    val view by viewModel.dayView.collectAsState()
    val display by viewModel.display.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val selected by viewModel.selected.collectAsState()

    Scaffold(
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = viewModel::showPreviousDay) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.day_previous))
                            }
                            Text(
                                text = dayTitle(view),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.clickable { picking = true }.padding(horizontal = 4.dp),
                            )
                            IconButton(onClick = viewModel::showNextDay) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.day_next))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.day_refresh))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                        IconButton(onClick = onSignOut) {
                            Icon(Icons.Filled.Logout, contentDescription = stringResource(R.string.sign_out))
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_cancel))
                        }
                    },
                    title = { Text("${selected.size}", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = viewModel::likeSelected) {
                            Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.viewer_like))
                        }
                        IconButton(onClick = { onShareSelected(viewModel.selectedItems()) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.viewer_share))
                        }
                        IconButton(onClick = { onDownloadSelected(viewModel.selectedItems()) }) {
                            Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.viewer_save))
                        }
                    },
                )
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (val current = view) {
                DayViewState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                DayViewState.NoPhotos ->
                    Text(
                        stringResource(R.string.day_no_photos),
                        Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )

                is DayViewState.Shown ->
                    if (current.hasPhotos) {
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            DayGrid(current, display, auth, likedKeys, selected, gridState, viewModel, onOpenPhoto)
                        }
                    } else {
                        Text(
                            stringResource(R.string.day_empty),
                            Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
            }
        }
    }

    if (picking) {
        DayPickerDialog(
            onDismiss = { picking = false },
            onPicked = { monthDay -> viewModel.showDay(monthDay); picking = false },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DayGrid(
    shown: DayViewState.Shown,
    display: List<DaySection>,
    auth: ThumbnailAuth,
    likedKeys: Set<String>,
    selected: Set<String>,
    gridState: LazyGridState,
    viewModel: DayViewModel,
    onOpenPhoto: (Int) -> Unit,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (shown.isFallback) {
            fullWidth {
                val direction = if (shown.inThePast) R.string.day_ago else R.string.day_ahead
                val days = pluralStringResource(R.plurals.day_count, shown.fallbackDays, shown.fallbackDays)
                Text(
                    stringResource(R.string.day_fallback, days, stringResource(direction)),
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // Running index across every section, matching DayViewModel.viewerSnapshot order.
        var index = 0
        display.forEach { section ->
            val header = section.header
            val groupKey = when (header) {
                DaySectionHeader.Liked -> "liked"
                is DaySectionHeader.LikedYear -> "liked-y${header.year}"
                is DaySectionHeader.Year -> "y${header.year}"
            }
            fullWidth {
                Row(
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val liked = header is DaySectionHeader.Liked || header is DaySectionHeader.LikedYear
                    if (liked) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp).size(18.dp),
                        )
                    }
                    Column {
                        val title = when (header) {
                            DaySectionHeader.Liked -> stringResource(R.string.liked_header)
                            is DaySectionHeader.LikedYear -> header.year.toString()
                            is DaySectionHeader.Year -> header.year.toString()
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            pluralStringResource(R.plurals.day_photo_count, section.items.size, section.items.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (section.items.isEmpty() && section.loading) {
                fullWidth {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                    }
                }
            }
            section.error?.let { message ->
                fullWidth {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
            }
            val start = index
            itemsIndexed(
                items = section.items,
                key = { _, item -> "$groupKey:${item.space.name}:${item.id}" },
            ) { localIndex, item ->
                PhotoCell(item, auth, likedKeys, selected, viewModel, onOpenPhoto, start + localIndex)
            }
            index += section.items.size
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun PhotoCell(
    item: com.hawwwran.photosonthisday.api.PhotoItem,
    auth: ThumbnailAuth,
    likedKeys: Set<String>,
    selected: Set<String>,
    viewModel: DayViewModel,
    onOpenPhoto: (Int) -> Unit,
    globalIndex: Int,
) {
    val isSelected = selected.contains(likeKey(item.space, item.unitId))
    val selectionActive = selected.isNotEmpty()

    // Double-tap like/unlike with a heart burst, like the viewer's photos elsewhere.
    var burstToken by remember { mutableIntStateOf(0) }
    var burstLiked by remember { mutableStateOf(false) }
    val burstScale = remember { Animatable(0f) }
    val burstAlpha = remember { Animatable(0f) }
    LaunchedEffect(burstToken) {
        if (burstToken == 0) return@LaunchedEffect
        burstScale.snapTo(0.4f)
        burstAlpha.snapTo(1f)
        burstScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        delay(350)
        burstAlpha.animateTo(0f, tween(200))
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = { if (selectionActive) viewModel.toggleSelect(item) else onOpenPhoto(globalIndex) },
                onLongClick = { viewModel.startSelect(item) },
                onDoubleClick = if (selectionActive) null else {
                    {
                        burstLiked = !likedKeys.contains(likeKey(item.space, item.unitId))
                        viewModel.toggleLike(item)
                        burstToken++
                    }
                },
            ),
    ) {
        Thumbnail(
            ref = ThumbnailRef(item.space, item.unitId, item.cacheKey, ThumbnailSize.MEDIUM),
            auth = auth,
            isVideo = item.isVideo,
            modifier = Modifier.fillMaxSize(),
        )
        if (likedKeys.contains(likeKey(item.space, item.unitId))) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
            )
        }
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp),
            )
        }
        if (burstAlpha.value > 0f) {
            Icon(
                imageVector = if (burstLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .graphicsLayer {
                        scaleX = burstScale.value
                        scaleY = burstScale.value
                        alpha = burstAlpha.value
                    },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun DayPickerDialog(onDismiss: () -> Unit, onPicked: (MonthDay) -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onPicked(MonthDay(date.monthValue, date.dayOfMonth))
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.day_pick_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.day_pick_cancel)) } },
    ) {
        // Only the month and day of the chosen date are used; the year is ignored.
        DatePicker(state = state)
    }
}

/** A full-row item in the grid: headers and notices span every column. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun dayTitle(view: DayViewState): String = when (view) {
    is DayViewState.Shown -> view.monthDay.czech()
    else -> stringResource(R.string.app_name)
}
