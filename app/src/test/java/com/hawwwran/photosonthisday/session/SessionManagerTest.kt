package com.hawwwran.photosonthisday.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.hawwwran.photosonthisday.api.AuthApi
import com.hawwwran.photosonthisday.api.SynologyClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
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
import java.util.concurrent.TimeUnit

class SessionManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: SessionStore
    private val wipes = mutableListOf<String>()
    private val thumbWipes = mutableListOf<String>()
    private lateinit var sessions: SessionManager

    @Before
    fun start() {
        server.start()
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            folder.newFile("session.preferences_pb")
        }
        store = SessionStore(dataStore)
        sessions = SessionManager(
            auth = AuthApi(SynologyClient(OkHttpClient())),
            store = store,
            wipers = listOf(AccountDataWiper { wipes += "wiped" }),
            accountChangeOnlyWipers = listOf(AccountDataWiper { thumbWipes += "thumbs" }),
        )
    }

    @After
    fun stop() {
        server.close()
        storeScope.cancel()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    private fun loginOk(sid: String) = """{"success":true,"data":{"sid":"$sid","synotoken":"T","device_id":"D"}}"""

    @Test
    fun `a good login stores the session and the device id, and wipes nothing`() = runTest {
        enqueue(loginOk("S1"))

        val outcome = sessions.signIn(server.url("/"), " anna ", "pw", otpCode = null)

        assertEquals(SessionManager.SignInOutcome.Success, outcome)
        val state = store.state.first() as SessionState.SignedIn
        assertEquals("anna", state.session.account)
        assertEquals("S1", state.session.credentials.sid)
        assertEquals("D", store.deviceId())
        assertTrue(wipes.isEmpty())
    }

    @Test
    fun `a wrong password is one request, DSM's text, and no session`() = runTest {
        enqueue("""{"success":false,"error":{"code":400}}""")

        val outcome = sessions.signIn(server.url("/"), "anna", "nope", null) as SessionManager.SignInOutcome.Failed

        assertTrue(outcome.message, "400" in outcome.message)
        assertFalse(outcome.needsOtp)
        assertEquals(1, server.requestCount)
        assertTrue(store.state.first() is SessionState.SignedOut)
    }

    @Test
    fun `a two-factor demand asks for the code`() = runTest {
        enqueue("""{"success":false,"error":{"code":403}}""")

        val outcome = sessions.signIn(server.url("/"), "anna", "pw", null) as SessionManager.SignInOutcome.Failed

        assertTrue(outcome.needsOtp)
    }

    @Test
    fun `signing in as another account wipes before saving`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)
        enqueue("""{"success":true}""")
        sessions.signOut()
        wipes.clear()

        enqueue(loginOk("S2"))
        sessions.signIn(server.url("/"), "bob", "pw", null)

        assertEquals(listOf("wiped"), wipes)
        assertEquals(listOf("thumbs"), thumbWipes) // account change also clears the thumbnail cache
        assertEquals("bob", (store.state.first() as SessionState.SignedIn).session.account)
    }

    @Test
    fun `the same account signing in again keeps its data`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)
        sessions.onSessionExpired("S1")
        val expired = store.state.first() as SessionState.SignedOut
        assertTrue(expired.expired)
        assertEquals("anna", expired.lastAccount)

        enqueue(loginOk("S2"))
        sessions.signIn(server.url("/"), "anna", "pw", null)

        assertTrue(wipes.isEmpty())
        assertTrue("same-account re-login keeps the thumbnail cache", thumbWipes.isEmpty())
        assertEquals("S2", (store.state.first() as SessionState.SignedIn).session.credentials.sid)
    }

    @Test
    fun `an expiry seen by an old session leaves the current one signed in`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)
        enqueue("""{"success":true}""")
        sessions.signOut()
        enqueue(loginOk("S2"))
        sessions.signIn(server.url("/"), "anna", "pw", null)

        // A stale view model still holding S1 meets DSM 119 and reports it after S2 is live.
        sessions.onSessionExpired("S1")

        val state = store.state.first() as SessionState.SignedIn
        assertEquals("S2", state.session.credentials.sid)
    }

    @Test
    fun `an expiry seen by the current session signs it out`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)

        sessions.onSessionExpired("S1")

        val state = store.state.first() as SessionState.SignedOut
        assertTrue(state.expired)
    }

    @Test
    fun `the same account name on another NAS is another account and wipes`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)
        enqueue("""{"success":true}""")
        sessions.signOut()
        wipes.clear()
        val otherNas = MockWebServer().also { it.start() }
        try {
            otherNas.enqueue(MockResponse.Builder().code(200).body(loginOk("S9")).build())

            sessions.signIn(otherNas.url("/"), "anna", "pw", null)

            assertEquals(listOf("wiped"), wipes)
            assertEquals(listOf("thumbs"), thumbWipes)
        } finally {
            otherNas.close()
        }
    }

    @Test
    fun `sign-out logs out, forgets the credentials, keeps the address, and wipes`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)
        server.takeRequest(5, TimeUnit.SECONDS)!!
        enqueue("""{"success":true}""")

        sessions.signOut()

        val logout = server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()
        assertTrue(logout, "method=logout" in logout && "_sid=S1" in logout)
        val state = store.state.first() as SessionState.SignedOut
        assertFalse(state.expired)
        assertEquals("anna", state.lastAccount)
        assertEquals(server.url("/").toString(), state.lastBaseUrl)
        assertEquals(listOf("wiped"), wipes)
        assertTrue("sign-out keeps the thumbnail cache for a same-account re-login", thumbWipes.isEmpty())
    }

    @Test
    fun `sign-out still completes when the NAS cannot be reached`() = runTest {
        enqueue(loginOk("S1"))
        sessions.signIn(server.url("/"), "anna", "pw", null)
        server.close()

        sessions.signOut()

        assertTrue(store.state.first() is SessionState.SignedOut)
        assertEquals(listOf("wiped"), wipes)
    }
}
