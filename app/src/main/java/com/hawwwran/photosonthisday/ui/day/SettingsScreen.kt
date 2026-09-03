package com.hawwwran.photosonthisday.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.hawwwran.photosonthisday.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: where the app points, how often it refreshes, the cache footprint and a way to clear
 * it, and sign out. The base URL and account are shown but not edited here; changing them means
 * signing out, which is the one-account-per-install reset (decision 006).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    baseUrl: String,
    account: String,
    refreshHours: Long,
    likesFolder: String,
    onLikesFolderChange: (String) -> Unit,
    mergeLiked: Boolean,
    onMergeLikedChange: (Boolean) -> Unit,
    onClearCache: suspend () -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheGeneration by remember { mutableStateOf(0) }

    val cacheBytes by produceState(initialValue = -1L, cacheGeneration) {
        value = withContext(Dispatchers.IO) {
            SingletonImageLoader.get(context).diskCache?.size ?: 0L
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.viewer_back))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Setting(stringResource(R.string.settings_nas), baseUrl)
            Setting(stringResource(R.string.settings_account), account)
            Setting(stringResource(R.string.settings_refresh), stringResource(R.string.settings_refresh_value, refreshHours))
            HorizontalDivider()
            var folder by remember(likesFolder) { mutableStateOf(likesFolder) }
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text(stringResource(R.string.settings_likes_folder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (folder.trim().trimEnd('/') != likesFolder) {
                OutlinedButton(onClick = { onLikesFolderChange(folder) }) {
                    Text(stringResource(R.string.settings_likes_folder_save))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_merge_liked), modifier = Modifier.weight(1f))
                Switch(checked = mergeLiked, onCheckedChange = onMergeLikedChange)
            }
            HorizontalDivider()
            Setting(
                stringResource(R.string.settings_cache),
                if (cacheBytes < 0) stringResource(R.string.settings_cache_measuring) else formatBytes(cacheBytes),
            )
            OutlinedButton(onClick = {
                scope.launch { onClearCache(); cacheGeneration++ }
            }) {
                Text(stringResource(R.string.settings_clear_cache))
            }
            HorizontalDivider()
            Button(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
    }
}

@Composable
private fun Setting(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
