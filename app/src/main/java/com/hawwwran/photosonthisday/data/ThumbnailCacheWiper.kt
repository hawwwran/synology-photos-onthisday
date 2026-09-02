package com.hawwwran.photosonthisday.data

import android.content.Context
import coil3.SingletonImageLoader
import com.hawwwran.photosonthisday.session.AccountDataWiper

/**
 * Clears Coil's memory and disk caches on a change of account (decision 006). Uses the singleton
 * loader so it clears the very caches the grid reads. If no image has been loaded yet the loader
 * still exists but its caches are empty, so this is a no-op then.
 */
class ThumbnailCacheWiper(private val context: Context) : AccountDataWiper {
    override suspend fun wipe() {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
