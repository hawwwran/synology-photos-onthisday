package com.hawwwran.photosonthisday.update

import android.content.Context

/** Which versions the user chose to skip. */
interface SkippedVersions {
    fun isDismissed(version: String): Boolean
    fun dismiss(version: String)
}

/**
 * Small SharedPreferences store for the update flow. Kept apart from the session DataStore because
 * it is device state, not account state, and it is read synchronously from the view model.
 */
class UpdatePrefs(context: Context) : SkippedVersions {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    private val dismissed: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, null) ?: emptySet()

    override fun isDismissed(version: String): Boolean = version in dismissed

    override fun dismiss(version: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissed + version).apply()
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_versions"
    }
}
