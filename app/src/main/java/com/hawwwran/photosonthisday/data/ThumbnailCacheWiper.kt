package com.hawwwran.photosonthisday.data

import android.content.Context
import coil3.SingletonImageLoader
import com.hawwwran.photosonthisday.session.AccountDataWiper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears Coil's memory and disk caches: on a change of account (decision 006) and from Settings.
 * Uses the singleton loader so it clears the very caches the grid reads. The disk clear deletes
 * files synchronously, so it runs on IO; sign-in calls this from the main dispatcher.
 */
class ThumbnailCacheWiper(private val context: Context) : AccountDataWiper {
    override suspend fun wipe() {
        withContext(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }
}
