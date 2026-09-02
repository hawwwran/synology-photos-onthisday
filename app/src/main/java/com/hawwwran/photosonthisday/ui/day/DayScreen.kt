package com.hawwwran.photosonthisday.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.core.DaySelection
import com.hawwwran.photosonthisday.data.DayIndexState

/**
 * A summary of the chosen day: enough to prove the index end to end (fetch, store, select),
 * short of the grid and thumbnails plan 004 brings. Names the day, its years and counts, and
 * whether it is a fallback.
 */
@Composable
fun DayScreen(viewModel: DayViewModel, account: String, host: String, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val error by viewModel.refreshError.collectAsState()

    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val current = state) {
                DayIndexState.Loading ->
                    if (refreshing) CircularProgressIndicator() else Text(stringResource(R.string.day_loading))

                DayIndexState.NoPhotos ->
                    Text(stringResource(R.string.day_no_photos), textAlign = TextAlign.Center)

                is DayIndexState.Ready -> DaySummary(current.selection)
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            Button(onClick = { viewModel.refresh(force = true) }, enabled = !refreshing) {
                Text(stringResource(R.string.day_refresh))
            }
            Text(
                stringResource(R.string.day_signed_in_as, account, host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
    }
}

@Composable
private fun DaySummary(selection: DaySelection) {
    val md = selection.monthDay
    Text(
        stringResource(R.string.day_heading, md.day, md.month),
        style = MaterialTheme.typography.headlineSmall,
    )
    if (selection.isFallback) {
        val direction = if (selection.inThePast) R.string.day_ago else R.string.day_ahead
        Text(
            stringResource(R.string.day_fallback, selection.daysFromToday, stringResource(direction)),
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Text(stringResource(R.string.day_total, selection.totalItems, selection.years.size))
    selection.years.forEach { year ->
        Text(stringResource(R.string.day_year_row, year.year, year.itemCount))
    }
}
