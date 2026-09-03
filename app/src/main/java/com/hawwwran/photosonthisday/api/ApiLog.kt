package com.hawwwran.photosonthisday.api

import android.util.Log

/**
 * The only place the network layer writes to the log, so that what it may say is decided
 * once: the call's name and a code. No parameter, no URL, no body, ever. `LoggingRuleTest`
 * checks that no other file logs and that no logger here takes free text.
 */
internal object ApiLog {
    private const val TAG = "PhotosApi"

    fun ok(call: ApiCall) {
        Log.d(TAG, "${call.name}: ok")
    }

    /** [ApiFailure] builds its message from the call name and a code or a fixed detail (`HardeningTest`). */
    fun failure(failure: ApiFailure) {
        Log.w(TAG, failure.message ?: failure.javaClass.simpleName)
    }
}
