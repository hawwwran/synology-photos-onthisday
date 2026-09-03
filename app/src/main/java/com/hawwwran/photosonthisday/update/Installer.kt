package com.hawwwran.photosonthisday.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands an APK to the system installer via `ACTION_VIEW` through the app's [FileProvider]. On
 * Android O+ the install is gated on `REQUEST_INSTALL_PACKAGES`; when it is not granted this
 * sends the user to the "install unknown apps" settings and returns [MISSING_PERMISSION] so the
 * caller can explain. Must run on the main thread.
 */
object Installer {

    enum class InstallStartOutcome { LAUNCHED, FILE_GONE, MISSING_PERMISSION, ERROR }

    fun installApk(context: Context, apk: File): InstallStartOutcome {
        if (!apk.exists()) return InstallStartOutcome.FILE_GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.i("OtdUpdate", "settings redirect failed: ${e.message}")
            }
            return InstallStartOutcome.MISSING_PERMISSION
        }

        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            InstallStartOutcome.LAUNCHED
        } catch (e: Exception) {
            Log.i("OtdUpdate", "install error: ${e.message}")
            InstallStartOutcome.ERROR
        }
    }
}
