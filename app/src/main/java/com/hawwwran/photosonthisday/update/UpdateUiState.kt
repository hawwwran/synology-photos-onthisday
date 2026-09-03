package com.hawwwran.photosonthisday.update

import java.io.File

/** Why an update step failed. The screen maps each to its own text; no message string travels. */
enum class UpdateFailure {
    /** GitHub answered the download with an error status. */
    DOWNLOAD_HTTP,

    /** The connection ended before `Content-Length` bytes arrived; the installer would reject the file. */
    DOWNLOAD_INCOMPLETE,

    /** No connection, or the file could not be written. */
    DOWNLOAD_IO,

    /** The downloaded APK was gone when the installer was asked for it. */
    FILE_GONE,

    /** The system installer could not be started. */
    INSTALLER,
}

/** UI states for the update flow; drives both the Settings entry and the modal. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    /** Nothing newer is known. [stale] when that comes from the cache because GitHub could not be reached. */
    data class NoUpdate(val currentVersion: String, val stale: Boolean = false) : UpdateUiState

    /** The check could not reach GitHub and nothing is cached, so nothing is known. Not "up to date". */
    data object CheckFailed : UpdateUiState

    /** A newer release exists. [dismissed] is true when the user skipped [info]'s version. */
    data class Available(val info: UpdateInfo, val dismissed: Boolean) : UpdateUiState

    /** A forced check is in flight; [previous] is what to revert to if it is cancelled. */
    data class Checking(val previous: UpdateUiState) : UpdateUiState

    data class Downloading(val info: UpdateInfo, val progress: Float, val bytesRead: Long, val total: Long) : UpdateUiState
    data class Launching(val info: UpdateInfo) : UpdateUiState

    /**
     * The APK is downloaded to [file], but Android does not yet allow this app to install packages;
     * the system page for that permission has been opened. The modal explains and waits; a return
     * with the permission granted continues the install without downloading again.
     */
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateUiState

    data class Error(val reason: UpdateFailure) : UpdateUiState
}
