package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.Space
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Plan 010 A: a local write failure is the device's fault, not the NAS's, and is told apart. */
class OriginalFetchTest {

    private val server = MockWebServer()
    private val http = OkHttpClient()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private suspend fun fetch(onBody: (String, java.io.InputStream) -> Unit) =
        OriginalFetch.fetch(http, server.url("/"), Space.PERSONAL, 7, "S", "T", onBody)

    @Test
    fun `a file that streams and writes is a success`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("JPEG").build())
        var written = ""

        val failure = fetch { mime, source -> written = "$mime:${source.readBytes().decodeToString()}" }

        assertNull(failure)
        assertEquals("image/jpeg:JPEG", written)
        val request = server.takeRequest()
        assertEquals("S", request.url.queryParameter("_sid"))
        assertEquals("T", request.headers["X-SYNO-TOKEN"])
    }

    @Test
    fun `a write that fails on the device is LOCAL, not TRANSPORT`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "video/mp4").body("MP4").build())

        val failure = fetch { _, _ -> throw IOException("MediaStore refused the insert") }

        assertEquals(FetchFailure.LOCAL, failure)
    }

    @Test
    fun `a stream that breaks while copying is TRANSPORT`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg")
                .body("JPEG".repeat(4096))
                .onResponseBody(SocketEffect.CloseSocket(closeSocket = true))
                .build(),
        )

        val failure = fetch { _, source -> source.readBytes() }

        assertEquals(FetchFailure.TRANSPORT, failure)
    }

    @Test
    fun `a JSON envelope with HTTP 200 is NOT_A_FILE`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"success":false,"error":{"code":119}}""").build())

        assertEquals(FetchFailure.NOT_A_FILE, fetch { _, _ -> })
    }

    @Test
    fun `no answer at all is TRANSPORT`() = runTest {
        val url = server.url("/")
        server.close()

        assertEquals(FetchFailure.TRANSPORT, OriginalFetch.fetch(http, url, Space.SHARED, 7, "S", null) { _, _ -> })
    }
}
