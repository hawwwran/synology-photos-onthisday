package com.hawwwran.photosonthisday.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.photosonthisday.api.BaseUrlResult
import com.hawwwran.photosonthisday.api.parseBaseUrl
import com.hawwwran.photosonthisday.session.SessionManager
import com.hawwwran.photosonthisday.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Form state for the sign-in screen. Submitting is the only thing that talks to the NAS, and
 * it happens once per tap: a failure re-enables the button and says what DSM said, nothing
 * retries on its own.
 */
class SignInViewModel(
    private val sessions: SessionManager,
    initial: SessionState.SignedOut,
) : ViewModel() {

    data class UiState(
        val host: String,
        val account: String,
        val password: String = "",
        val otpCode: String = "",
        /** True once DSM has asked for a code, so the field appears. */
        val otpRequested: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        /** How many attempts have failed; the auto-block note appears after the first. */
        val failures: Int = 0,
        /** The previous session was ended by the NAS, which the screen should say. */
        val expiredNotice: Boolean,
    ) {
        val canSubmit: Boolean
            get() = !busy && host.isNotBlank() && account.isNotBlank() && password.isNotEmpty()
    }

    private val _state = MutableStateFlow(
        UiState(
            host = initial.lastBaseUrl ?: "",
            account = initial.lastAccount ?: "",
            expiredNotice = initial.expired,
        ),
    )
    val state: StateFlow<UiState> = _state

    fun onHostChange(value: String) = _state.update { it.copy(host = value, error = null) }
    fun onAccountChange(value: String) = _state.update { it.copy(account = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onOtpChange(value: String) = _state.update { it.copy(otpCode = value.filter(Char::isDigit), error = null) }

    fun submit() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        // Typed text becomes a URL here and nowhere else; http:// stops before any request.
        val baseUrl = when (val parsed = parseBaseUrl(snapshot.host)) {
            is BaseUrlResult.Ok -> parsed.url
            is BaseUrlResult.Refused -> {
                _state.update { it.copy(error = parsed.reason) }
                return
            }
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = sessions.signIn(
                baseUrl = baseUrl,
                account = snapshot.account,
                password = snapshot.password,
                otpCode = snapshot.otpCode.takeIf { it.isNotBlank() },
            )
            // The password leaves the screen state whatever happened; a retry means retyping it.
            when (outcome) {
                is SessionManager.SignInOutcome.Success ->
                    _state.update { it.copy(busy = false, password = "", otpCode = "") }
                is SessionManager.SignInOutcome.Failed ->
                    _state.update {
                        it.copy(
                            busy = false,
                            password = "",
                            otpCode = if (outcome.needsOtp) it.otpCode else "",
                            otpRequested = it.otpRequested || outcome.needsOtp,
                            error = outcome.message,
                            failures = it.failures + 1,
                        )
                    }
            }
        }
    }
}
