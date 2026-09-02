package com.hawwwran.photosonthisday.ui.day

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.SingletonImageLoader
import com.hawwwran.photosonthisday.AppGraph
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.core.currentMonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.SaveResult
import com.hawwwran.photosonthisday.data.ShareResult
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the signed-in screens is showing. Reset to the grid on any account change (new host). */
private sealed interface DayNav {
    data object Grid : DayNav
    data class Viewer(val index: Int) : DayNav
    data object Settings : DayNav
}

/**
 * Holds the one [DayViewModel] and the navigation between the day grid, the fullscreen viewer and
 * settings. A three-screen flow, so a small in-memory nav state rather than a NavHost.
 */
@Composable
fun DayHost(graph: AppGraph, session: Session, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: DayViewModel = viewModel(
        key = "day-${session.credentials.sid}",
        factory = viewModelFactory { initializer { DayViewModel(graph.dayIndex, session, currentMonthDay()) } },
    )
    val auth = ThumbnailAuth(session.baseUrl, session.credentials.sid, session.credentials.synotoken)
    val viewerItems by viewModel.viewerItems.collectAsState()

    var nav by remember { mutableStateOf<DayNav>(DayNav.Grid) }
    var saving by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    when (val current = nav) {
        DayNav.Grid -> DayScreen(
            viewModel = viewModel,
            auth = auth,
            onOpenPhoto = { index -> nav = DayNav.Viewer(index) },
            onOpenSettings = { nav = DayNav.Settings },
            onSignOut = onSignOut,
        )

        is DayNav.Viewer -> {
            BackHandler { nav = DayNav.Grid }
            ViewerScreen(
                items = viewerItems,
                startIndex = current.index,
                auth = auth,
                onBack = { nav = DayNav.Grid },
                saving = saving,
                sharing = sharing,
                onShare = { entry ->
                    if (!sharing) scope.launch {
                        sharing = true
                        val result = graph.mediaSharer.prepare(
                            session.baseUrl, entry.item.space, entry.item.unitId, auth.sid, auth.token,
                        )
                        sharing = false
                        when (result) {
                            is ShareResult.Ready -> {
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", result.file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = result.mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, context.getString(R.string.viewer_share)))
                            }
                            is ShareResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSave = { entry ->
                    if (!saving) scope.launch {
                        saving = true
                        val result = graph.imageSaver.save(
                            session.baseUrl, entry.item.space, entry.item.unitId, auth.sid, auth.token,
                        )
                        saving = false
                        val message = when (result) {
                            SaveResult.Success -> context.getString(R.string.viewer_saved)
                            is SaveResult.Failed -> result.reason
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        DayNav.Settings -> {
            BackHandler { nav = DayNav.Grid }
            SettingsScreen(
                baseUrl = session.baseUrl.toString(),
                account = session.account,
                refreshHours = DayIndexRepository.DEFAULT_STALE_AFTER / (60 * 60 * 1000L),
                onClearCache = {
                    withContext(Dispatchers.IO) {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                    }
                },
                onBack = { nav = DayNav.Grid },
                onSignOut = onSignOut,
            )
        }
    }
}
