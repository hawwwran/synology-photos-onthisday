package com.hawwwran.photosonthisday.data

import android.util.Log
import com.hawwwran.photosonthisday.api.Space

/** The data layer's only log line. A count and a namespace name, never a day or a photo. */
internal object IndexLog {
    private const val TAG = "PhotosIndex"

    fun countMismatch(space: Space, flattened: Int, reported: Int) {
        Log.w(TAG, "${space.name}: histogram sums to $flattened, count says $reported; index refreshed anyway")
    }
}
