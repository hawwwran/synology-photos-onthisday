package com.hawwwran.photosonthisday.update

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hawwwran.photosonthisday.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Activity-scoped view model for the update flow. Two cancellation domains: [checkJob] (the
 * version check, cancelled when the activity backgrounds) and [downloadJob] (the APK download,
 * kept alive across a minimize by the downloader's wake lock; only the user's Cancel stops it).
 * The collaborators are interfaces so the state machine is tested on the JVM; [factory] wires the
 * real ones over the app's HTTP client.
 */
class UpdateViewModel(
    private val checker: UpdateChecking,
    private val downloader: UpdateDownloading,
    private val installer: UpdateInstalling,
    private val prefs: SkippedVersions,
    private val currentVersion: String,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _modalOpen = MutableStateFlow(false)
    val modalOpen: StateFlow<Boolean> = _modalOpen.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * On resume. Back from the install-permission page with the permission granted, the install
     * continues with the file already downloaded. Otherwise a rate-limited check (a no-op cache hit
     * within 24 h) that never opens the modal.
     */
    fun onAppOpen() {
        when (val s = _state.value) {
            is UpdateUiState.NeedsPermission -> {
                if (installer.canInstall()) startInstall(s.info, s.file)
                return
            }
            is UpdateUiState.Checking, is UpdateUiState.Downloading, is UpdateUiState.Launching -> return
            else -> {}
        }
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

    /**
     * User tapped Install. Downloads the APK (or reuses a complete one), then fires the system
     * installer. From [UpdateUiState.NeedsPermission] it retries the install, which reopens the
     * permission page if it is still missing.
     */
    fun onInstall() {
        when (val s = _state.value) {
            is UpdateUiState.NeedsPermission -> startInstall(s.info, s.file)
            is UpdateUiState.Available -> startDownload(s.info)
            else -> return
        }
    }

    private fun startDownload(info: UpdateInfo) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                _state.value = UpdateUiState.Downloading(info, 0f, 0L, 0L)
                downloader.download(info.apkUrl, info.latestVersion, info.apkSize).collect { event ->
                    when (event) {
                        is UpdateDownloader.DownloadProgress.Started -> {}
                        is UpdateDownloader.DownloadProgress.Progress -> {
                            val pct = if (event.total > 0) event.bytesRead.toFloat() / event.total else 0f
                            _state.value = UpdateUiState.Downloading(info, pct, event.bytesRead, event.total)
                        }
                        is UpdateDownloader.DownloadProgress.Done -> startInstall(info, event.file)
                        is UpdateDownloader.DownloadProgress.Failed -> _state.value = UpdateUiState.Error(event.reason)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = UpdateUiState.Available(info, dismissed = prefs.isDismissed(info.latestVersion))
                throw e
            }
        }
    }

    /** Main thread: the view model scope. */
    private fun startInstall(info: UpdateInfo, file: File) {
        _state.value = UpdateUiState.Launching(info)
        viewModelScope.launch {
            when (installer.install(file)) {
                Installer.InstallStartOutcome.LAUNCHED -> {
                    delay(LAUNCHING_TO_IDLE_DELAY_MS)
                    _state.value = UpdateUiState.Idle
                    _modalOpen.value = false
                }
                Installer.InstallStartOutcome.MISSING_PERMISSION -> {
                    _state.value = UpdateUiState.NeedsPermission(info, file)
                    _modalOpen.value = true
                }
                Installer.InstallStartOutcome.FILE_GONE -> _state.value = UpdateUiState.Error(UpdateFailure.FILE_GONE)
                Installer.InstallStartOutcome.ERROR -> _state.value = UpdateUiState.Error(UpdateFailure.INSTALLER)
            }
        }
    }

    private suspend fun runCheck(force: Boolean, openModalOnDone: Boolean) {
        try {
            _state.value = when (val outcome = checker.check(force)) {
                is CheckOutcome.Found ->
                    if (outcome.info.isNewer) UpdateUiState.Available(outcome.info, dismissed = prefs.isDismissed(outcome.info.latestVersion))
                    else UpdateUiState.NoUpdate(currentVersion, stale = outcome.info.stale)
                CheckOutcome.NoRelease -> UpdateUiState.NoUpdate(currentVersion)
                CheckOutcome.Unreachable -> UpdateUiState.CheckFailed
            }
            if (openModalOnDone) _modalOpen.value = true
        } catch (e: CancellationException) {
            val current = _state.value
            if (current is UpdateUiState.Checking) _state.value = current.previous
            throw e
        } catch (e: IOException) {
            _state.value = UpdateUiState.CheckFailed
        }
    }

    private fun stableSnapshot(): UpdateUiState {
        val s = _state.value
        return if (s is UpdateUiState.Checking) s.previous else s
    }

    companion object {
        private const val LAUNCHING_TO_IDLE_DELAY_MS = 800L

        /** The real collaborators, over the app's HTTP client. */
        fun factory(application: Application, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val currentVersion = try {
                    application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.0.0"
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    "0.0.0"
                }
                UpdateViewModel(
                    checker = UpdateChecker(
                        cacheFile = File(application.cacheDir, "update-check.json"),
                        currentVersion = currentVersion,
                        appClient = graph.http,
                    ),
                    downloader = UpdateDownloader.forContext(application, graph.http),
                    installer = Installer.forContext(application),
                    prefs = UpdatePrefs(application),
                    currentVersion = currentVersion,
                )
            }
        }
    }
}
