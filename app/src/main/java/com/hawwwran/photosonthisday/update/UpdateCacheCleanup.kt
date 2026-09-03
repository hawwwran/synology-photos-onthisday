package com.hawwwran.photosonthisday.update

import android.content.Context
import java.io.File

/**
 * Prunes APKs cached by [UpdateDownloader] older than [MAX_AGE_DAYS], called once per process
 * from [MainActivity] so a completed install (or a download the user walked away from) does not
 * leave ~17 MB on disk indefinitely.
 */
object UpdateCacheCleanup {

    private const val MAX_AGE_DAYS = 7

    fun pruneOldUpdates(context: Context) {
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS.toLong() * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }
}
