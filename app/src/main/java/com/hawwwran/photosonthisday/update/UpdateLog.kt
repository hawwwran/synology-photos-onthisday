package com.hawwwran.photosonthisday.update

import android.util.Log

/**
 * The update flow's log lines: a reason class and, at most, an exception's class name. Never a
 * message, a URL or a file path, so the same rule as `ApiLog` holds here (`LoggingRuleTest`).
 */
internal object UpdateLog {
    private const val TAG = "OtdUpdate"

    fun downloadFailed(reason: UpdateFailure, cause: Exception? = null) {
        Log.i(TAG, "download failed: $reason ${cause?.javaClass?.simpleName.orEmpty()}")
    }

    fun installFailed(cause: Exception) {
        Log.i(TAG, "install error: ${cause.javaClass.simpleName}")
    }

    fun settingsRedirectFailed(cause: Exception) {
        Log.i(TAG, "settings redirect failed: ${cause.javaClass.simpleName}")
    }
}
