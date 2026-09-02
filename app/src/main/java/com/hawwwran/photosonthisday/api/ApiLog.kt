package com.hawwwran.photosonthisday.api

import android.util.Log
import java.io.IOException

/**
 * The only place the network layer writes to the log, so that what it may say is decided
 * once: the call's name and a code. No parameter, no URL, no body, ever.
 */
internal object ApiLog {
    private const val TAG = "PhotosApi"

    fun ok(call: ApiCall) {
        Log.d(TAG, "${call.name}: ok")
    }

    fun dsmError(call: ApiCall, code: Int) {
        Log.w(TAG, "${call.name}: DSM error $code")
    }

    fun transport(call: ApiCall, cause: IOException) {
        Log.w(TAG, "${call.name}: ${cause.javaClass.simpleName}")
    }

    fun malformed(call: ApiCall, detail: String) {
        Log.w(TAG, "${call.name}: $detail")
    }
}
