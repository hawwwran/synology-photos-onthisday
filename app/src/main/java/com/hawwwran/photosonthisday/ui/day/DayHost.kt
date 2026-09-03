package com.hawwwran.photosonthisday.ui.day

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hawwwran.photosonthisday.AppGraph
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.core.currentMonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.FetchFailure
import com.hawwwran.photosonthisday.data.SaveResult
import com.hawwwran.photosonthisday.data.ShareResult
import com.hawwwran.photosonthisday.data.fileProviderAuthority
import com.hawwwran.photosonthisday.session.Session
import com.hawwwran.photosonthisday.session.SessionStore
import com.hawwwran.photosonthisday.update.UpdateViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

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
fun DayHost(graph: AppGraph, session: Session, updateViewModel: UpdateViewModel, onSignOut: () -> Unit) {
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
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun reasonText(reason: FetchFailure) = context.getString(reason.stringId())

    // One save path and one share path for the viewer (one item) and the grid selection (many).
    val saveItems: (List<PhotoItem>) -> Unit = { items ->
        if (!saving && items.isNotEmpty()) scope.launch {
            saving = true
            val results = items.map { graph.imageSaver.save(session.baseUrl, it.space, it.unitId, auth.sid, auth.token) }
            saving = false
            viewModel.clearSelection()
            val saved = results.count { it is SaveResult.Success }
            val firstFailure = results.filterIsInstance<SaveResult.Failed>().firstOrNull()
            toast(
                when {
                    items.size == 1 && firstFailure != null -> reasonText(firstFailure.reason)
                    items.size == 1 -> context.getString(R.string.viewer_saved)
                    else -> context.getString(R.string.selection_saved, saved, items.size)
                },
            )
        }
    }
    val shareItems: (List<PhotoItem>) -> Unit = { items ->
        if (!sharing && items.isNotEmpty()) scope.launch {
            sharing = true
            val results = items.map { graph.mediaSharer.prepare(session.baseUrl, it.space, it.unitId, auth.sid, auth.token) }
            sharing = false
            viewModel.clearSelection()
            val ready = results.filterIsInstance<ShareResult.Ready>()
            if (ready.isEmpty()) {
                val reason = results.filterIsInstance<ShareResult.Failed>().firstOrNull()?.reason
                toast(if (reason != null) reasonText(reason) else context.getString(R.string.selection_share_failed))
            } else {
                val authority = fileProviderAuthority(context)
                val uris = ArrayList<Uri>(ready.map { FileProvider.getUriForFile(context, authority, it.file) })
                val mimes = ready.map { it.mime }.toSet()
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimes.single()
                        putExtra(Intent.EXTRA_STREAM, uris.single())
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = mimes.singleOrNull() ?: commonMime(mimes)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.viewer_share)))
            }
        }
    }

    when (val current = nav) {
        DayNav.Grid -> DayScreen(
            viewModel = viewModel,
            auth = auth,
            likedKeys = likedKeys,
            gridState = gridState,
            onOpenPhoto = { index -> nav = DayNav.Viewer(index) },
            onOpenSettings = { nav = DayNav.Settings },
            onSignOut = onSignOut,
            onDownloadSelected = saveItems,
            onShareSelected = shareItems,
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
                http = graph.http,
                likedKeys = likedKeys,
                onToggleLike = { entry -> viewModel.toggleLike(entry.item) },
                onBack = { nav = DayNav.Grid },
                resolvePath = { item -> graph.folderApi.path(session.baseUrl, item.space, item.folderId, session.credentials) },
                saving = saving,
                sharing = sharing,
                onShare = { entry -> shareItems(listOf(entry.item)) },
                onSave = { entry -> saveItems(listOf(entry.item)) },
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
                updateViewModel = updateViewModel,
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

private fun FetchFailure.stringId(): Int = when (this) {
    FetchFailure.NOT_A_FILE -> R.string.fetch_failed_not_a_file
    FetchFailure.TRANSPORT -> R.string.fetch_failed_transport
    FetchFailure.LOCAL -> R.string.fetch_failed_local
}

/** A shared mime for a multi-share: image or video when uniform, otherwise a wildcard. */
private fun commonMime(mimes: Set<String>): String = when {
    mimes.all { it.startsWith("image/") } -> "image/*"
    mimes.all { it.startsWith("video/") } -> "video/*"
    else -> "*/*"
}
