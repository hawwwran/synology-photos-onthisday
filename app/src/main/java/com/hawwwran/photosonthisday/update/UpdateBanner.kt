package com.hawwwran.photosonthisday.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.ui.theme.Palette

/** Whether [UpdateBanner] draws anything for [state]; `AppRoot` consumes the status-bar inset below it when it does. */
fun updateBannerShown(state: UpdateUiState): Boolean = state is UpdateUiState.Available && !state.dismissed

/**
 * Top-of-app update bar. Shows only when an update is available and the user has not skipped that
 * version. Tapping opens the modal against the existing state, without a fresh network check. The
 * amber ground and dark text are deliberate (an attention colour), so they do not follow theme.
 */
@Composable
fun UpdateBanner(state: UpdateUiState, onClick: () -> Unit) {
    if (!updateBannerShown(state)) return
    val info = (state as UpdateUiState.Available).info
    Surface(
        color = Palette.Amber,
        contentColor = Palette.Night,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding() // edge-to-edge (targetSdk 35): sit below the status bar, not under it
            .heightIn(max = 56.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.update_banner, info.latestVersion),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text("›", fontWeight = FontWeight.Bold)
        }
    }
}
