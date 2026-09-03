package com.hawwwran.photosonthisday.session

import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.AuthApi
import com.hawwwran.photosonthisday.api.DsmErrorText
import okhttp3.HttpUrl

/**
 * Something that holds one account's data and can throw it away: the day index (plan 003),
 * the thumbnail cache (plan 004). Registered in the app graph; [SessionManager] calls every
 * one before another account's first byte is shown (decision 006).
 */
fun interface AccountDataWiper {
    suspend fun wipe()
}

/** Sign-in, sign-out and expiry, with decision 006's wipe in the right places. */
class SessionManager(
    private val auth: AuthApi,
    private val store: SessionStore,
    /** Cleared on sign-out and on account change: the day index and item rows. */
    private val wipers: List<AccountDataWiper>,
    /**
     * Cleared on account change only: the thumbnail cache. A same-account sign-out keeps it, so
     * signing back in does not re-download (plan 004 acceptance); a different account still wipes
     * it before any of its data is shown, so nothing leaks (decision 006, amended 2026-09-02).
     */
    private val accountChangeOnlyWipers: List<AccountDataWiper> = emptyList(),
) {

    sealed interface SignInOutcome {
        data object Success : SignInOutcome

        /** [needsOtp] asks the screen to show the two-factor field. */
        data class Failed(val message: String, val needsOtp: Boolean = false) : SignInOutcome
    }

    /**
     * One login attempt, never retried here or anywhere: DSM auto-block bans the address after
     * a few failures. [password] is passed straight through and not kept. [baseUrl] has already
     * been through [com.hawwwran.photosonthisday.api.parseBaseUrl] at the screen, which is where
     * typed text becomes a URL and where `http://` is refused.
     */
    suspend fun signIn(
        baseUrl: HttpUrl,
        account: String,
        password: String,
        otpCode: String?,
    ): SignInOutcome {
        val accountName = account.trim()
        if (accountName.isEmpty()) return SignInOutcome.Failed("Enter the account name.")
        if (password.isEmpty()) return SignInOutcome.Failed("Enter the password.")

        val result = try {
            auth.login(baseUrl, accountName, password, otpCode, store.deviceId())
        } catch (e: ApiFailure.DsmError) {
            return SignInOutcome.Failed(DsmErrorText.forLogin(e.code), needsOtp = e.code in OTP_CODES)
        } catch (e: ApiFailure) {
            return SignInOutcome.Failed(DsmErrorText.forFailure(e))
        }

        // Decision 006: another account's data is destroyed before the new one shows anything.
        // The identity is the NAS and the name together: "anna" on another NAS is someone else.
        val previous = store.lastIdentity()
        val current = AccountIdentity(baseUrl.toString(), accountName)
        if (previous != null && previous != current) {
            wipeAll()
            accountChangeOnlyWipers.forEach { it.wipe() }
        }

        store.save(Session(baseUrl, accountName, result.credentials), result.deviceId)
        return SignInOutcome.Success
    }

    /** Ends the session on the NAS when it can, then forgets it and wipes the account's data. */
    suspend fun signOut() {
        val session = store.current()
        if (session != null) {
            try {
                auth.logout(session.baseUrl, session.credentials)
            } catch (e: ApiFailure) {
                // The local state is what matters; a NAS that cannot be reached still gets signed out of.
            }
        }
        store.clearCredentials()
        wipeAll()
    }

    /**
     * Called by whoever catches [ApiFailure.SessionExpired], with the session id that call used.
     * Only the stored session can be expired by it: a view model that outlived its sign-out and
     * still holds an old sid must not sign the new session out (decision 003, amended
     * 2026-09-03).
     */
    suspend fun onSessionExpired(sid: String) {
        store.markExpired(sid)
    }

    // The thumbnail cache is deliberately not wiped here: a same-account sign-out keeps it.
    private suspend fun wipeAll() {
        wipers.forEach { it.wipe() }
    }

    private companion object {
        /** 403 code required, 404 wrong code, 406 two-factor enforced: all want the field shown. */
        val OTP_CODES = setOf(403, 404, 406)
    }
}
