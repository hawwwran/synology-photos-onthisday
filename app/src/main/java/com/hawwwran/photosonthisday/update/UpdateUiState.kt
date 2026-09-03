package com.hawwwran.photosonthisday.update

/** UI states for the update flow; drives both the Settings entry and the modal. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class NoUpdate(val currentVersion: String) : UpdateUiState

    /** A newer release exists. [dismissed] is true when the user skipped [info]'s version. */
    data class Available(val info: UpdateInfo, val dismissed: Boolean) : UpdateUiState

    /** A forced check is in flight; [previous] is what to revert to if it is cancelled. */
    data class Checking(val previous: UpdateUiState) : UpdateUiState

    data class Downloading(val info: UpdateInfo, val progress: Float, val bytesRead: Long, val total: Long) : UpdateUiState
    data class Launching(val info: UpdateInfo) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}
