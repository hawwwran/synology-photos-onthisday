package com.hawwwran.photosonthisday.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hawwwran.photosonthisday.AppGraph
import com.hawwwran.photosonthisday.session.SessionState
import com.hawwwran.photosonthisday.ui.day.DayPlaceholderScreen
import com.hawwwran.photosonthisday.ui.signin.SignInScreen
import com.hawwwran.photosonthisday.ui.signin.SignInViewModel
import kotlinx.coroutines.launch

/**
 * Sign-in when there is no session, the day screen when there is. The session store is the
 * single source of truth, so expiry detected anywhere flips this screen without any
 * navigation call.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    val state by graph.sessionStore.state.collectAsState(initial = SessionState.Loading)
    val scope = rememberCoroutineScope()

    when (val current = state) {
        SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is SessionState.SignedOut -> {
            // Keyed on the expiry flag so a session that ends mid-form shows the notice.
            val viewModel: SignInViewModel = viewModel(
                key = "signin-${current.expired}",
                factory = viewModelFactory {
                    initializer { SignInViewModel(graph.sessions, current) }
                },
            )
            SignInScreen(viewModel)
        }

        is SessionState.SignedIn -> DayPlaceholderScreen(
            account = current.session.account,
            host = current.session.baseUrl.host,
            onSignOut = { scope.launch { graph.sessions.signOut() } },
        )
    }
}
