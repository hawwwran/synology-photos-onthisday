package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.assertFailsWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The likes file and a File Station error both arrive as HTTP 200; the body's shape decides. */
class FileStationClientTest {

    private val server = MockWebServer()
    private val client = FileStationClient(OkHttpClient())
    private val store = LikesNasStore(client)
    private val credentials = SessionCredentials("S", "T")

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())
    }

    @Test
    fun `an HTML page with HTTP 200 on upload is Malformed, not success`() = runTest {
        enqueue("<html>proxy</html>")

        assertFailsWith<ApiFailure.Malformed> { client.upload(server.url("/"), "/home/OnThisDay", "likes.json", "{}".toByteArray(), credentials) }
    }

    @Test
    fun `a success envelope confirms the upload`() = runTest {
        enqueue("""{"success":true,"data":{"blSkip":false,"file":"likes.json"}}""")

        client.upload(server.url("/"), "/home/OnThisDay", "likes.json", "{}".toByteArray(), credentials)

        val request = server.takeRequest()
        assertEquals("S", request.url.queryParameter("_sid"))
        assertEquals("T", request.headers["X-SYNO-TOKEN"])
    }

    @Test
    fun `a dead session on upload is SessionExpired`() = runTest {
        enqueue("""{"success":false,"error":{"code":119}}""")

        val failure = assertFailsWith<ApiFailure.SessionExpired> { client.upload(server.url("/"), "/x", "likes.json", "{}".toByteArray(), credentials) }

        assertEquals(119, failure.code)
    }

    @Test
    fun `a dead session on download is SessionExpired`() = runTest {
        enqueue("""{"success":false,"error":{"code":119}}""")

        assertFailsWith<ApiFailure.SessionExpired> { client.download(server.url("/"), "/x/likes.json", credentials) }
    }

    @Test
    fun `a missing file is null, whether DSM says 404 or 408`() = runTest {
        enqueue("", code = 404)
        assertNull(client.download(server.url("/"), "/x/likes.json", credentials))

        enqueue("""{"success":false,"error":{"code":408}}""")
        assertNull(client.download(server.url("/"), "/x/likes.json", credentials))
    }

    @Test
    fun `a likes file without a Content-Disposition header is the file`() = runTest {
        val body = """{"version":1,"likes":[{"key":"PERSONAL:5","liked":true,"at":10}]}"""
        enqueue(body)

        val states = store.pull(server.url("/"), "/home/OnThisDay", credentials)

        assertEquals(listOf(LikeState("PERSONAL:5", true, 10)), states)
    }

    @Test
    fun `a file that exists but is not the likes shape is Malformed, so nothing overwrites it`() = runTest {
        enqueue("this is somebody's notes", 200, "Content-Disposition" to "attachment; filename=likes.json")
        assertFailsWith<ApiFailure.Malformed> { store.pull(server.url("/"), "/home/OnThisDay", credentials) }

        enqueue("""{"version":1,"likes":"nope"}""")
        assertFailsWith<ApiFailure.Malformed> { store.pull(server.url("/"), "/home/OnThisDay", credentials) }
    }

    @Test
    fun `a success envelope where a file was expected is Malformed`() = runTest {
        enqueue("""{"success":true}""")

        val failure = assertFailsWith<ApiFailure.Malformed> { client.download(server.url("/"), "/x/likes.json", credentials) }

        assertTrue(failure.message!!, "envelope" in failure.message!!)
    }
}
