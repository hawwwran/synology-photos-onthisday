package com.hawwwran.photosonthisday.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hawwwran.photosonthisday.AppGraph
import com.hawwwran.photosonthisday.session.SessionState
import com.hawwwran.photosonthisday.ui.day.DayHost
import com.hawwwran.photosonthisday.ui.signin.SignInScreen
import com.hawwwran.photosonthisday.ui.signin.SignInViewModel
import com.hawwwran.photosonthisday.update.UpdateBanner
import com.hawwwran.photosonthisday.update.UpdateModal
import com.hawwwran.photosonthisday.update.UpdateViewModel
import kotlinx.coroutines.launch

/**
 * Sign-in when there is no session, the day screen when there is. The session store is the
 * single source of truth, so expiry detected anywhere flips this screen without a navigation
 * call. The update banner and modal live here, above whichever screen is shown; the update view
 * model is activity-scoped, so Settings reaches the same instance through `viewModel()`.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    val state by graph.sessionStore.state.collectAsState(initial = SessionState.Loading)
    val scope = rememberCoroutineScope()

    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsState()
    val updateModalOpen by updateViewModel.modalOpen.collectAsState()

    // Auto-check on resume (rate-limited to 24 h by the on-disk cache); abort a check in flight
    // when the activity backgrounds.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> updateViewModel.onAppOpen()
                Lifecycle.Event.ON_STOP -> updateViewModel.cancelInFlightCheck()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        UpdateBanner(state = updateState, onClick = updateViewModel::openModal)
        Box(Modifier.weight(1f)) {
            when (val current = state) {
                SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                is SessionState.SignedOut -> {
                    val viewModel: SignInViewModel = viewModel(
                        key = "signin-${current.expired}",
                        factory = viewModelFactory { initializer { SignInViewModel(graph.sessions, current) } },
                    )
                    SignInScreen(viewModel)
                }

                is SessionState.SignedIn -> DayHost(
                    graph = graph,
                    session = current.session,
                    onSignOut = { scope.launch { graph.sessions.signOut() } },
                )
            }
        }
    }

    UpdateModal(
        state = updateState,
        open = updateModalOpen,
        onInstall = updateViewModel::onInstall,
        onSkip = updateViewModel::onSkipVersion,
        onDismiss = updateViewModel::onDismissModal,
        onCancelDownload = updateViewModel::onCancelDownload,
        onRetry = updateViewModel::onForceCheck,
    )
}
