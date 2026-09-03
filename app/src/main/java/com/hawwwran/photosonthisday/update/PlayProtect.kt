package com.hawwwran.photosonthisday.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens Google Play Protect's settings, where "scan apps with Play Protect" can be paused. Play
 * Protect runs on every sideloaded install and may reject an APK from a developer it does not
 * know; the app gets no result from the installer, so the dialog offers this page as the place
 * to look. The Play services action is undocumented but is what the Play Store itself launches;
 * the security settings page is the fallback on a phone without it.
 */
fun openPlayProtectSettings(context: Context) {
    val candidates = listOf(
        Intent(PLAY_PROTECT_SETTINGS),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next one
        }
    }
}

private const val PLAY_PROTECT_SETTINGS = "com.google.android.gms.settings.VERIFY_APPS_SETTINGS"
