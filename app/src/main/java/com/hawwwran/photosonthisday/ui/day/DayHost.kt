package com.hawwwran.photosonthisday.ui.day

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hawwwran.photosonthisday.AppGraph
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.core.currentMonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.SaveResult
import com.hawwwran.photosonthisday.data.ShareResult
import com.hawwwran.photosonthisday.session.Session
import com.hawwwran.photosonthisday.session.SessionStore
import kotlinx.coroutines.launch

/** Which of the signed-in screens is showing. Reset to the grid on any account change (new host). */
private sealed interface DayNav {
    data object Grid : DayNav
    data class Viewer(val index: Int) : DayNav
    data object Settings : DayNav
}

/**
 * Holds the one [DayViewModel] and the navigation between the day grid, the fullscreen viewer and
 * settings. A three-screen flow, so a small in-memory nav state rather than a NavHost.
 *
 * The view model lives exactly as long as this composable does, in a store this composable owns.
 * In the Activity's store it survived sign-out: the old instance kept observing the index, saw
 * the new session's first write, fetched with its dead sid, and signed the new session out on
 * DSM's 119 (plan 007). A view model tied to one [session] must die with it.
 */
@Composable
fun DayHost(graph: AppGraph, session: Session, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storeOwner = remember(session) { DayViewModelStoreOwner() }
    DisposableEffect(storeOwner) { onDispose { storeOwner.viewModelStore.clear() } }
    val viewModel: DayViewModel = viewModel(
        viewModelStoreOwner = storeOwner,
        factory = viewModelFactory { initializer { DayViewModel(graph.dayIndex, graph.likes, session, currentMonthDay(), graph.sessionStore.likedByYear()) } },
    )
    val auth = ThumbnailAuth(session.baseUrl, session.credentials.sid, session.credentials.synotoken)
    val likedKeys by viewModel.likedKeys.collectAsState()
    val likesNotice by viewModel.likesNotice.collectAsState()
    LaunchedEffect(likesNotice) {
        likesNotice?.let {
            Toast.makeText(context, context.getString(R.string.likes_sync_failed, it), Toast.LENGTH_LONG).show()
            viewModel.likesNoticeShown()
        }
    }
    val likesFolder by graph.sessionStore.likesFolder().collectAsState(initial = SessionStore.DEFAULT_LIKES_FOLDER)
    val likedByYear by graph.sessionStore.likedByYear().collectAsState(initial = false)

    var nav by remember { mutableStateOf<DayNav>(DayNav.Grid) }
    // Hoisted here, so the grid keeps its scroll position while the viewer or settings is on top.
    val gridState = rememberLazyGridState()

    // Whenever the app comes to the front, roll to today if the calendar date has changed since
    // it was last in front (e.g. midnight passed while locked). A same-day return does nothing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var lastDate = LocalDate.now()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = LocalDate.now()
                if (now != lastDate) {
                    viewModel.refreshToday()
                    lastDate = now
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var saving by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    when (val current = nav) {
        DayNav.Grid -> DayScreen(
            viewModel = viewModel,
            auth = auth,
            likedKeys = likedKeys,
            gridState = gridState,
            onOpenPhoto = { index -> nav = DayNav.Viewer(index) },
            onOpenSettings = { nav = DayNav.Settings },
            onSignOut = onSignOut,
            onDownloadSelected = { items ->
                if (!saving && items.isNotEmpty()) scope.launch {
                    saving = true
                    var ok = 0
                    for (item in items) {
                        val r = graph.imageSaver.save(session.baseUrl, item.space, item.unitId, auth.sid, auth.token)
                        if (r is com.hawwwran.photosonthisday.data.SaveResult.Success) ok++
                    }
                    saving = false
                    viewModel.clearSelection()
                    Toast.makeText(context, context.getString(R.string.selection_saved, ok, items.size), Toast.LENGTH_SHORT).show()
                }
            },
            onShareSelected = { items ->
                if (!sharing && items.isNotEmpty()) scope.launch {
                    sharing = true
                    val uris = ArrayList<Uri>()
                    val mimes = HashSet<String>()
                    for (item in items) {
                        val r = graph.mediaSharer.prepare(session.baseUrl, item.space, item.unitId, auth.sid, auth.token)
                        if (r is ShareResult.Ready) {
                            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", r.file)
                            mimes += r.mime
                        }
                    }
                    sharing = false
                    viewModel.clearSelection()
                    if (uris.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.selection_share_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        val type = mimes.singleOrNull() ?: commonMime(mimes)
                        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            this.type = type
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.viewer_share)))
                    }
                }
            },
        )

        is DayNav.Viewer -> {
            BackHandler { nav = DayNav.Grid }
            // A stable snapshot: liking or unliking in the viewer must not reshuffle the pager
            // (the grid re-sorts liked-first on return, but the open viewer keeps its order).
            val items = remember(current) { viewModel.viewerSnapshot() }
            ViewerScreen(
                items = items,
                startIndex = current.index,
                auth = auth,
                likedKeys = likedKeys,
                onToggleLike = { entry -> viewModel.toggleLike(entry.item) },
                onBack = { nav = DayNav.Grid },
                resolvePath = { item -> graph.folderApi.path(session.baseUrl, item.space, item.folderId, session.credentials) },
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
                likesFolder = likesFolder,
                onLikesFolderChange = { path -> scope.launch { graph.sessionStore.setLikesFolder(path) } },
                likedByYear = likedByYear,
                onLikedByYearChange = { value -> scope.launch { graph.sessionStore.setLikedByYear(value) } },
                onClearCache = { graph.thumbnailWiper.wipe() },
                onBack = { nav = DayNav.Grid },
                onSignOut = onSignOut,
            )
        }
    }
}

/** One [ViewModelStore] per signed-in session, cleared when [DayHost] leaves the composition. */
private class DayViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** A shared mime for a multi-share: image or video when uniform, otherwise a wildcard. */
private fun commonMime(mimes: Set<String>): String = when {
    mimes.all { it.startsWith("image/") } -> "image/*"
    mimes.all { it.startsWith("video/") } -> "video/*"
    else -> "*/*"
}
