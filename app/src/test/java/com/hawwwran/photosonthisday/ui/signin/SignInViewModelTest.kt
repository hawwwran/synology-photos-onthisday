package com.hawwwran.photosonthisday.ui.signin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.hawwwran.photosonthisday.api.AuthApi
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.session.SessionManager
import com.hawwwran.photosonthisday.session.SessionState
import com.hawwwran.photosonthisday.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var viewModel: SignInViewModel

    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        val store = SessionStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { folder.newFile("s.preferences_pb") },
        )
        val sessions = SessionManager(AuthApi(SynologyClient(OkHttpClient())), store, emptyList())
        viewModel = SignInViewModel(sessions, SessionState.SignedOut(expired = true, lastBaseUrl = null, lastAccount = "anna"))
    }

    @After
    fun stop() {
        server.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun fill(host: String) {
        viewModel.onHostChange(host)
        viewModel.onPasswordChange("pw")
    }

    @Test
    fun `starts prefilled from the last session and says it expired`() {
        val state = viewModel.state.value
        assertEquals("anna", state.account)
        assertTrue(state.expiredNotice)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `an http address is refused on the screen and nothing is sent`() = runTest {
        fill("http://nas.local:5000")

        viewModel.submit()

        val state = viewModel.state.value
        assertTrue(state.error!!, "https://" in state.error!!)
        assertEquals(0, server.requestCount)
        assertEquals("the NAS was never asked, so no failure is counted", 0, state.failures)
        assertFalse(state.busy)
    }
}
