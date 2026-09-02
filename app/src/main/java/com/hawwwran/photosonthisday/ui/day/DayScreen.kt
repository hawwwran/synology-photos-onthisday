package com.hawwwran.photosonthisday.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.api.ThumbnailRef
import com.hawwwran.photosonthisday.api.ThumbnailSize
import com.hawwwran.photosonthisday.core.DaySelection
import com.hawwwran.photosonthisday.data.DayIndexState

/**
 * The chosen day: a title naming it (and how far from today when it is a fallback), then a
 * year-by-year grid, newest year first. One `LazyVerticalGrid` carries every year, with a
 * full-width header per year, so a day of hundreds of photos scrolls as one list and thumbnails
 * load only as their cells appear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(viewModel: DayViewModel, auth: ThumbnailAuth, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val sections by viewModel.sections.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(dayTitle(state)) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.day_refresh))
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Filled.Logout, contentDescription = stringResource(R.string.sign_out))
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (val current = state) {
                DayIndexState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                DayIndexState.NoPhotos ->
                    Text(
                        stringResource(R.string.day_no_photos),
                        Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )

                is DayIndexState.Ready -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    DayGrid(current.selection, sections, auth)
                }
            }
        }
    }
}

@Composable
private fun DayGrid(selection: DaySelection, sections: List<YearSection>, auth: ThumbnailAuth) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (selection.isFallback) {
            fullWidth {
                val direction = if (selection.inThePast) R.string.day_ago else R.string.day_ahead
                Text(
                    stringResource(R.string.day_fallback, selection.daysFromToday, stringResource(direction)),
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        sections.forEach { section ->
            fullWidth {
                Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                    Text("${section.year}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.day_year_count, section.expectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            itemsIndexed(
                items = section.items,
                key = { _, item -> "${section.year}:${item.space.name}:${item.id}" },
            ) { _, item ->
                Thumbnail(
                    ref = ThumbnailRef(item.space, item.unitId, item.cacheKey, ThumbnailSize.MEDIUM),
                    auth = auth,
                    isVideo = item.isVideo,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

/** A full-row item in the grid: headers and notices span every column. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun dayTitle(state: DayIndexState): String = when (state) {
    is DayIndexState.Ready -> stringResource(
        R.string.day_heading,
        state.selection.monthDay.day,
        state.selection.monthDay.month,
    )
    else -> stringResource(R.string.app_name)
}
