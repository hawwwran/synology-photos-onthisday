package com.hawwwran.photosonthisday.update

import android.content.Context

/**
 * Small SharedPreferences store for the update flow: which versions the user chose to skip, and
 * when the last check ran. Kept apart from the session DataStore because it is device state, not
 * account state, and it is read synchronously from the view model.
 */
class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    private val dismissed: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, null) ?: emptySet()

    fun isDismissed(version: String): Boolean = version in dismissed

    fun dismiss(version: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissed + version).apply()
    }

    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    private companion object {
        const val KEY_DISMISSED = "dismissed_versions"
        const val KEY_LAST_CHECK = "last_check_at"
    }
}
