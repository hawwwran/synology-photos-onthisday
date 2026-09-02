package com.hawwwran.photosonthisday.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.hawwwran.photosonthisday.assertFailsWith
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthApiTest {

    private val server = MockWebServer()
    private val auth = AuthApi(SynologyClient(OkHttpClient()))

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun enqueue(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    @Test
    fun `login posts the v7 form and reads sid, synotoken and device_id`() = runTest {
        enqueue("""{"success":true,"data":{"sid":"S","synotoken":"T","device_id":"D","account":"anna","is_portal_port":false,"ik_message":""}}""")

        val result = auth.login(server.url("/"), "anna", "p@ss word", otpCode = "123456", deviceId = null)

        assertEquals(SessionCredentials("S", "T"), result.credentials)
        assertEquals("D", result.deviceId)
        val request = server.takeRequest()
        val body = request.body!!.utf8()
        for (expected in listOf(
            "api=SYNO.API.Auth", "method=login", "version=7", "account=anna",
            "passwd=p%40ss+word", "format=sid", "enable_syno_token=yes", "enable_device_token=yes",
            "otp_code=123456", "device_name=On+This+Day",
        )) {
            assertTrue("missing $expected in $body", expected in body)
        }
        assertFalse("no device_id param when none is known", "device_id=" in body)
        assertNull("the password never goes in the URL", request.url.queryParameter("passwd"))
        assertEquals("/webapi/entry.cgi", request.url.encodedPath)
    }

    @Test
    fun `a known trusted device is sent back and a blank code is not`() = runTest {
        enqueue("""{"success":true,"data":{"sid":"S","synotoken":"","device_id":""}}""")

        val result = auth.login(server.url("/"), "anna", "pw", otpCode = "  ", deviceId = "dev-1")

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, "device_id=dev-1" in body)
        assertFalse(body, "otp_code" in body)
        assertNull("empty synotoken reads as none", result.credentials.synotoken)
        assertNull("empty device_id reads as none", result.deviceId)
    }

    @Test
    fun `wrong credentials surface as DsmError 400 and nothing is retried`() = runTest {
        enqueue("""{"success":false,"error":{"code":400}}""")

        val failure = assertFailsWith<ApiFailure.DsmError> { auth.login(server.url("/"), "anna", "wrong") }

        assertEquals(400, failure.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a success without a sid is Malformed`() = runTest {
        enqueue("""{"success":true,"data":{"synotoken":"T"}}""")

        assertFailsWith<ApiFailure.Malformed> { auth.login(server.url("/"), "anna", "pw") }
    }

    @Test
    fun `logout carries the session`() = runTest {
        enqueue("""{"success":true}""")

        auth.logout(server.url("/"), SessionCredentials("S", "T"))

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertTrue(body, "method=logout" in body)
        assertTrue(body, "_sid=S" in body)
        assertEquals("T", request.headers["X-SYNO-TOKEN"])
    }
}
