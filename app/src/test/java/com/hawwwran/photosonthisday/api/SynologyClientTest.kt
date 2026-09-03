package com.hawwwran.photosonthisday.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.hawwwran.photosonthisday.assertFailsWith
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SynologyClientTest {

    private val server = MockWebServer()
    private val client = SynologyClient(OkHttpClient())
    private val credentials = SessionCredentials(sid = "sid-123", synotoken = "tok-456")

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test
    fun `success envelope yields data and the request carries the triple, the sid and the token`() = runTest {
        enqueue("""{"success":true,"data":{"count":3}}""")

        val data = client.call(server.url("/"), Allowlist.itemCount(Space.PERSONAL), credentials = credentials)

        assertEquals(3, data.jsonObject["count"]?.jsonPrimitive?.intOrNull)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/webapi/entry.cgi", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertTrue(body, "api=SYNO.Foto.Browse.Item" in body)
        assertTrue(body, "method=count" in body)
        assertTrue(body, "version=7" in body)
        assertTrue(body, "_sid=sid-123" in body)
        assertEquals("tok-456", request.headers["X-SYNO-TOKEN"])
        assertNull("the sid belongs in the body, never the URL", request.url.queryParameter("_sid"))
    }

    @Test
    fun `error envelope becomes a DsmError carrying the code`() = runTest {
        enqueue("""{"success":false,"error":{"code":120}}""")

        val failure = assertFailsWith<ApiFailure.DsmError> { client.call(server.url("/"), Allowlist.itemCount(Space.SHARED), credentials = credentials) }

        assertEquals(120, failure.code)
        assertEquals(Allowlist.itemCount(Space.SHARED), failure.call)
    }

    @Test
    fun `session codes become SessionExpired`() = runTest {
        for (code in listOf(106, 107, 119)) {
            enqueue("""{"success":false,"error":{"code":$code}}""")
            val failure = assertFailsWith<ApiFailure.SessionExpired> { client.call(server.url("/"), Allowlist.timeline(Space.PERSONAL), credentials = credentials) }
            assertEquals(code, failure.code)
        }
    }

    /** 105 says "not permitted", not "session gone": signing out on it would loop on every refresh. */
    @Test
    fun `insufficient privilege is a DsmError, not an expiry`() = runTest {
        enqueue("""{"success":false,"error":{"code":105}}""")

        val failure = assertFailsWith<ApiFailure.DsmError> { client.call(server.url("/"), Allowlist.timeline(Space.SHARED), credentials = credentials) }

        assertEquals(105, failure.code)
    }

    @Test
    fun `a success whose data is missing or not an object is Malformed for object callers`() = runTest {
        enqueue("""{"success":true}""")
        assertFailsWith<ApiFailure.Malformed> { client.callObject(server.url("/"), Allowlist.itemCount(Space.PERSONAL), credentials = credentials) }

        enqueue("""{"success":true,"data":[]}""")
        assertFailsWith<ApiFailure.Malformed> { client.callObject(server.url("/"), Allowlist.itemCount(Space.PERSONAL), credentials = credentials) }

        enqueue("""{"success":true,"data":{"count":1}}""")
        assertEquals(1, client.callObject(server.url("/"), Allowlist.itemCount(Space.PERSONAL), credentials = credentials)["count"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `a triple off the allowlist never reaches the network`() = runTest {
        val album = ApiCall("SYNO.Foto.Browse.Album", "list", 5)

        assertFailsWith<DisallowedCallException> { client.call(server.url("/"), album, credentials = credentials) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an HTTP error page is Malformed, not a DSM error`() = runTest {
        enqueue("<html>502 Bad Gateway</html>", code = 502)

        val failure = assertFailsWith<ApiFailure.Malformed> { client.call(server.url("/"), Allowlist.API_INFO) }

        assertTrue(failure.message!!, "HTTP 502" in failure.message!!)
    }

    @Test
    fun `a body that is not a Synology envelope is Malformed`() = runTest {
        enqueue("not json at all")
        assertFailsWith<ApiFailure.Malformed> { client.call(server.url("/"), Allowlist.API_INFO) }

        enqueue("""{"data":{}}""")
        assertFailsWith<ApiFailure.Malformed> { client.call(server.url("/"), Allowlist.API_INFO) }
    }

    @Test
    fun `an unreachable host is a Transport failure`() = runTest {
        val unreachable = server.url("/")
        server.close()

        val failure = assertFailsWith<ApiFailure.Transport> { client.call(unreachable, Allowlist.API_INFO) }

        assertEquals(Allowlist.API_INFO, failure.call)
    }

    @Test
    fun `calls without credentials send neither sid nor token`() = runTest {
        enqueue("""{"success":true,"data":{}}""")

        client.call(server.url("/"), Allowlist.API_INFO, params = mapOf("query" to "all"))

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertTrue(body, "query=all" in body)
        assertTrue(body, "_sid" !in body)
        assertNull(request.headers["X-SYNO-TOKEN"])
    }
}
