package com.hawwwran.photosonthisday.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Activity-scoped view model for the update flow. Two cancellation domains: [checkJob] (the
 * version check, cancelled when the activity backgrounds) and [downloadJob] (the APK download,
 * kept alive across a minimize by the downloader's wake lock; only the user's Cancel stops it).
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = UpdatePrefs(application)

    private val currentVersion: String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }

    private val checker = UpdateChecker(
        cacheFile = File(application.cacheDir, "update-check.json"),
        currentVersion = currentVersion,
    )
    private val downloader = UpdateDownloader.forContext(application)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _modalOpen = MutableStateFlow(false)
    val modalOpen: StateFlow<Boolean> = _modalOpen.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Auto-check on resume. A no-op cache hit within 24 h; never opens the modal. */
    fun onAppOpen() {
        val s = _state.value
        if (s is UpdateUiState.Checking || s is UpdateUiState.Downloading || s is UpdateUiState.Launching) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch { runCheck(force = false, openModalOnDone = false) }
    }

    /** User tapped "Check for updates" in Settings; always opens the modal. */
    fun onForceCheck() {
        checkJob?.cancel()
        _state.value = UpdateUiState.Checking(stableSnapshot())
        _modalOpen.value = true
        checkJob = viewModelScope.launch { runCheck(force = true, openModalOnDone = true) }
    }

    fun cancelInFlightCheck() {
        checkJob?.cancel()
    }

    /** User tapped the banner: open the modal against the existing state, no fresh check. */
    fun openModal() {
        _modalOpen.value = true
    }

    fun onDismissModal() {
        _modalOpen.value = false
    }

    fun onCancelDownload() {
        downloadJob?.cancel()
    }

    fun onSkipVersion() {
        val info = (_state.value as? UpdateUiState.Available)?.info ?: return
        prefs.dismiss(info.latestVersion)
        _state.value = UpdateUiState.Available(info, dismissed = true)
    }

    /** User tapped Install. Downloads the APK, then fires the system installer. */
    fun onInstall() {
        val info = (_state.value as? UpdateUiState.Available)?.info ?: return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                _state.value = UpdateUiState.Downloading(info, 0f, 0L, 0L)
                downloader.download(info.apkUrl, info.latestVersion).collect { event ->
                    when (event) {
                        is UpdateDownloader.DownloadProgress.Started -> {}
                        is UpdateDownloader.DownloadProgress.Progress -> {
                            val pct = if (event.total > 0) event.bytesRead.toFloat() / event.total else 0f
                            _state.value = UpdateUiState.Downloading(info, pct, event.bytesRead, event.total)
                        }
                        is UpdateDownloader.DownloadProgress.Done -> {
                            _state.value = UpdateUiState.Launching(info)
                            val outcome = withContext(Dispatchers.Main) { Installer.installApk(app, event.file) }
                            handleInstallOutcome(outcome)
                        }
                        is UpdateDownloader.DownloadProgress.Failed ->
                            _state.value = UpdateUiState.Error(event.reason)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = UpdateUiState.Available(info, dismissed = prefs.isDismissed(info.latestVersion))
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Error(e.message ?: "Update failed")
            }
        }
    }

    private suspend fun handleInstallOutcome(outcome: Installer.InstallStartOutcome) {
        when (outcome) {
            Installer.InstallStartOutcome.LAUNCHED,
            Installer.InstallStartOutcome.MISSING_PERMISSION -> {
                delay(LAUNCHING_TO_IDLE_DELAY_MS)
                _state.value = UpdateUiState.Idle
                _modalOpen.value = false
            }
            Installer.InstallStartOutcome.FILE_GONE ->
                _state.value = UpdateUiState.Error("Downloaded file missing")
            Installer.InstallStartOutcome.ERROR ->
                _state.value = UpdateUiState.Error("Could not start installer")
        }
    }

    private suspend fun runCheck(force: Boolean, openModalOnDone: Boolean) {
        try {
            val info = checker.check(force = force)
            _state.value = when {
                info == null -> UpdateUiState.NoUpdate(currentVersion)
                info.isNewer -> UpdateUiState.Available(info, dismissed = prefs.isDismissed(info.latestVersion))
                else -> UpdateUiState.NoUpdate(info.currentVersion)
            }
            if (openModalOnDone) _modalOpen.value = true
            prefs.lastCheckAt = System.currentTimeMillis()
        } catch (e: CancellationException) {
            val current = _state.value
            if (current is UpdateUiState.Checking) _state.value = current.previous
            throw e
        } catch (e: Exception) {
            _state.value = UpdateUiState.Error(e.message ?: "Update check failed")
        }
    }

    private fun stableSnapshot(): UpdateUiState {
        val s = _state.value
        return if (s is UpdateUiState.Checking) s.previous else s
    }

    private companion object {
        const val LAUNCHING_TO_IDLE_DELAY_MS = 800L
    }
}
