package com.hawwwran.photosonthisday.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hawwwran.photosonthisday.data.fileProviderAuthority
import java.io.File

/** The system installer, as the view model sees it; [Installer] is the real one. */
interface UpdateInstalling {
    /** Whether Android lets this app start a package install right now. */
    fun canInstall(): Boolean

    /** Must run on the main thread. */
    fun install(apk: File): Installer.InstallStartOutcome
}

/**
 * Hands an APK to the system installer via `ACTION_VIEW` through the app's [FileProvider]. The
 * install is gated on `REQUEST_INSTALL_PACKAGES`; when it is not granted this sends the user to
 * the "install unknown apps" settings and returns [InstallStartOutcome.MISSING_PERMISSION] so
 * the caller can explain and keep the file. Must run on the main thread.
 */
object Installer {

    enum class InstallStartOutcome { LAUNCHED, FILE_GONE, MISSING_PERMISSION, ERROR }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installApk(context: Context, apk: File): InstallStartOutcome {
        if (!apk.exists()) return InstallStartOutcome.FILE_GONE

        if (!canInstall(context)) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                UpdateLog.settingsRedirectFailed(e)
            }
            return InstallStartOutcome.MISSING_PERMISSION
        }

        return try {
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            InstallStartOutcome.LAUNCHED
        } catch (e: ActivityNotFoundException) {
            UpdateLog.installFailed(e)
            InstallStartOutcome.ERROR
        } catch (e: IllegalArgumentException) {
            // FileProvider: the file is outside the paths the manifest exposes.
            UpdateLog.installFailed(e)
            InstallStartOutcome.ERROR
        }
    }

    /** [UpdateInstalling] over a context, for the view model factory. */
    fun forContext(context: Context): UpdateInstalling = object : UpdateInstalling {
        override fun canInstall(): Boolean = canInstall(context)
        override fun install(apk: File): InstallStartOutcome = installApk(context, apk)
    }
}
