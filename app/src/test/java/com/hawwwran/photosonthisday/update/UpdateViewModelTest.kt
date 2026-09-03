package com.hawwwran.photosonthisday.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Plan 010 C: the update flow never tells the user something false. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val info = UpdateInfo("1.0.0", "1.1.0", "https://example/a.apk", 1234L, "notes", isNewer = true, stale = false)
    private val apk = File("OnThisDay-1.1.0.apk")

    private var outcome: CheckOutcome = CheckOutcome.Unreachable
    private var canInstall = false
    private val installs = ArrayList<File>()
    private val skipped = HashSet<String>()

    private val viewModel by lazy {
        UpdateViewModel(
            checker = { outcome },
            downloader = { _, _, _ -> flowOf(UpdateDownloader.DownloadProgress.Done(apk)) },
            installer = object : UpdateInstalling {
                override fun canInstall() = canInstall
                override fun install(apk: File): Installer.InstallStartOutcome {
                    installs += apk
                    return if (canInstall) Installer.InstallStartOutcome.LAUNCHED else Installer.InstallStartOutcome.MISSING_PERMISSION
                }
            },
            prefs = object : SkippedVersions {
                override fun isDismissed(version: String) = version in skipped
                override fun dismiss(version: String) { skipped += version }
            },
            currentVersion = "1.0.0",
        )
    }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `a forced check that cannot reach GitHub says so, not up to date`() = runTest {
        outcome = CheckOutcome.Unreachable

        viewModel.onForceCheck()

        assertEquals(UpdateUiState.CheckFailed, viewModel.state.value)
        assertTrue(viewModel.modalOpen.value)
    }

    @Test
    fun `a newer release is Available and an older one is NoUpdate`() = runTest {
        outcome = CheckOutcome.Found(info)
        viewModel.onForceCheck()
        assertEquals(UpdateUiState.Available(info, dismissed = false), viewModel.state.value)

        outcome = CheckOutcome.Found(info.copy(latestVersion = "0.9.0", isNewer = false))
        viewModel.onForceCheck()
        assertEquals(UpdateUiState.NoUpdate("1.0.0"), viewModel.state.value)
    }

    @Test
    fun `a missing install permission keeps the modal, the file and the state, and resumes once granted`() = runTest {
        outcome = CheckOutcome.Found(info)
        viewModel.onForceCheck()

        viewModel.onInstall()

        assertEquals(UpdateUiState.NeedsPermission(info, apk), viewModel.state.value)
        assertTrue("the modal stays to explain the settings page", viewModel.modalOpen.value)
        assertEquals(listOf(apk), installs)

        viewModel.onAppOpen() // back from settings, still not granted: nothing happens, no loop into settings
        assertEquals(UpdateUiState.NeedsPermission(info, apk), viewModel.state.value)
        assertEquals(1, installs.size)

        canInstall = true
        viewModel.onAppOpen() // back from settings with the permission

        assertEquals("installed from the file already downloaded", 2, installs.size)
        assertTrue(viewModel.state.value is UpdateUiState.Launching || viewModel.state.value is UpdateUiState.Idle)
    }

    @Test
    fun `skipping a version is remembered`() = runTest {
        outcome = CheckOutcome.Found(info)
        viewModel.onForceCheck()

        viewModel.onSkipVersion()

        assertEquals(UpdateUiState.Available(info, dismissed = true), viewModel.state.value)
        assertTrue("1.1.0" in skipped)
    }
}
