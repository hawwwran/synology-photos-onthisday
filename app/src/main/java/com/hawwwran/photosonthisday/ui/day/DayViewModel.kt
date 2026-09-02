package com.hawwwran.photosonthisday.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.DayIndexState
import com.hawwwran.photosonthisday.data.RefreshResult
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the day screen: the stored index as a flow (cache first), and a refresh on open that
 * fetches only when the index is stale. An expired session is handled inside the repository,
 * which flips the session store and so returns the app to sign-in without a navigation call.
 */
class DayViewModel(
    private val repository: DayIndexRepository,
    private val session: Session,
) : ViewModel() {

    val state: StateFlow<DayIndexState> =
        repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayIndexState.Loading)

    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        refresh(force = false)
    }

    /** Pull-to-refresh forces a fetch; open uses the stale check. */
    fun refresh(force: Boolean) {
        if (_refreshing.value) return
        _refreshing.value = true
        _refreshError.value = null
        viewModelScope.launch {
            val result = if (force) repository.refresh(session) else repository.refreshIfStale(session)
            when (result) {
                is RefreshResult.Failed -> _refreshError.value = result.message
                RefreshResult.Success, RefreshResult.SessionExpired -> Unit
            }
            _refreshing.value = false
        }
    }
}
