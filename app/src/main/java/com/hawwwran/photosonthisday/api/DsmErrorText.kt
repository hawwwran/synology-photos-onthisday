package com.hawwwran.photosonthisday.api

/**
 * DSM's error codes in plain language, for the screen. The raw code is always appended so
 * that what the user sees is still what DSM said (plan.md "Errors" convention).
 *
 * The Auth codes are from Synology's published Web API guide, the common codes likewise;
 * `documents/research/photos-web-api.md` lists both under "Signing in".
 */
object DsmErrorText {

    fun forLogin(code: Int): String = "${loginReason(code)} (DSM error $code)"

    fun forCall(code: Int): String = "${commonReason(code)} (DSM error $code)"

    fun forFailure(failure: ApiFailure): String = when (failure) {
        is ApiFailure.DsmError -> forCall(failure.code)
        is ApiFailure.SessionExpired -> "The NAS ended this session. Sign in again. (DSM error ${failure.code})"
        is ApiFailure.Transport -> "The NAS could not be reached. Check the address and the connection."
        is ApiFailure.Malformed -> "The address answered, but not as a Synology NAS would."
    }

    private fun loginReason(code: Int): String = when (code) {
        400 -> "Wrong account name or password."
        401 -> "This account is disabled."
        402 -> "This account is not allowed to sign in here."
        403 -> "A two-factor code is required."
        404 -> "The two-factor code was wrong."
        406 -> "Two-factor authentication is required for this account."
        407 -> "This address is blocked by the NAS. DSM auto-block lifts it after a while, or an administrator can."
        409 -> "The password has expired."
        410 -> "The password must be changed before signing in."
        else -> commonReason(code)
    }

    private fun commonReason(code: Int): String = when (code) {
        100 -> "The NAS reported an unknown error."
        101 -> "The NAS says a parameter was missing."
        102 -> "The NAS does not have this API."
        103 -> "The NAS does not have this method."
        104 -> "The NAS does not support this API version."
        105 -> "This account is not permitted to do that."
        106 -> "The session timed out."
        107 -> "This session was ended by another sign-in."
        119 -> "The session is no longer valid."
        120 -> "The NAS rejected a parameter."
        else -> "The NAS refused the request."
    }
}
