package com.hawwwran.photosonthisday.data

import android.util.Log
import com.hawwwran.photosonthisday.api.Space

/**
 * The index's log lines: counts and a namespace name, never a day, a photo, or anything from a
 * response. The same rule as `ApiLog` (call name and code, nothing else), checked by
 * `LoggingRuleTest`.
 */
internal object IndexLog {
    private const val TAG = "PhotosIndex"

    fun countMismatch(space: Space, flattened: Int, reported: Int) {
        Log.w(TAG, "${space.name}: histogram sums to $flattened, count says $reported; index refreshed anyway")
    }

    /** A day fetch disagreed with the histogram. [fetched] counts what the server sent, [dropped] of those had no thumbnail. */
    fun dayCountMismatch(fetched: Int, dropped: Int, expected: Int) {
        Log.w(TAG, "day fetch: $fetched items ($dropped without thumbnail), histogram says $expected; refresh scheduled")
    }
}
