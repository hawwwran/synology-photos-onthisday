package com.hawwwran.photosonthisday.api

import java.io.IOException

/**
 * Why a call produced no data. Messages name the call and the code, never a parameter or a
 * body: album and sharing responses carry live credentials (plan.md §2), so nothing that
 * could reach a log is allowed to carry response content.
 */
sealed class ApiFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val call: ApiCall

    /** DSM answered `success: false`. [code] is what it said. */
    class DsmError(override val call: ApiCall, val code: Int) :
        ApiFailure("${call.name}: DSM error $code")

    /** One of the codes that mean the session is gone; the user has to sign in again. */
    class SessionExpired(override val call: ApiCall, val code: Int) :
        ApiFailure("${call.name}: session gone, DSM error $code")

    /** The request never got an answer. The cause's class name is enough for the log. */
    class Transport(override val call: ApiCall, cause: IOException) :
        ApiFailure("${call.name}: ${cause.javaClass.simpleName}", cause)

    /**
     * An answer that is not a Synology envelope: an HTTP error page, a proxy notice, garbage.
     * [detail] is app-authored, from [MalformedDetail]'s vocabulary, never response content, so
     * the screen may show it and the log may carry it (plan.md §2, `HardeningTest`).
     */
    class Malformed(override val call: ApiCall, val detail: String) :
        ApiFailure("${call.name}: $detail")

    companion object {
        /**
         * From Synology's Web API guide: 106 session timeout, 107 session replaced by another
         * login, 119 sid not found. All three re-prompt. 105 (insufficient privilege) is not
         * here: it answers "this account may not do that", the session is fine, and signing the
         * user out would loop on every refresh (decision 003, amended 2026-09-03).
         */
        val SESSION_GONE_CODES = setOf(106, 107, 119)

        /** The failure a `success: false` envelope means: a dead session re-prompts, anything else is DSM's answer. */
        fun fromDsmCode(call: ApiCall, code: Int): ApiFailure =
            if (code in SESSION_GONE_CODES) SessionExpired(call, code) else DsmError(call, code)
    }
}
