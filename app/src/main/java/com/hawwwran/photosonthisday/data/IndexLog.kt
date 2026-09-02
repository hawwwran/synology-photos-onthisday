package com.hawwwran.photosonthisday.data

import android.util.Log
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.MonthDay

/** The data layer's only log line. A count and a namespace name, never a day or a photo. */
internal object IndexLog {
    private const val TAG = "PhotosIndex"

    fun countMismatch(space: Space, flattened: Int, reported: Int) {
        Log.w(TAG, "${space.name}: histogram sums to $flattened, count says $reported; index refreshed anyway")
    }

    fun dayCountMismatch(year: Int, monthDay: MonthDay, fetched: Int, expected: Int) {
        Log.w(TAG, "$year-${monthDay.month}-${monthDay.day}: fetched $fetched, histogram says $expected; scheduling a refresh")
    }
}
