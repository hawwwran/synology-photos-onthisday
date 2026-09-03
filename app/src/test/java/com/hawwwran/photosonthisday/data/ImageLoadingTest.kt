package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.acceptsImageResponse
import com.hawwwran.photosonthisday.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Research U4: a thumbnail GET with a dead session is HTTP 200 plus JSON. It must never reach the disk cache. */
class ImageLoadingTest {

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().addInterceptor(ImageResponseGuard()).build()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `the rule accepts only a 2xx image`() {
        assertTrue(acceptsImageResponse(200, "image/jpeg"))
        assertTrue(acceptsImageResponse(200, "image/webp; charset=binary"))
        assertTrue(acceptsImageResponse(200, "IMAGE/PNG"))
        assertFalse("the U4 envelope", acceptsImageResponse(200, "application/json"))
        assertFalse(acceptsImageResponse(200, null))
        assertFalse(acceptsImageResponse(200, ""))
        assertFalse(acceptsImageResponse(500, "image/jpeg"))
        assertFalse("a proxy error page", acceptsImageResponse(200, "text/html"))
    }

    @Test
    fun `a JSON envelope with HTTP 200 fails the request before any bytes are read`() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"success":false,"error":{"code":119}}""")
                .build(),
        )
        val url = server.url("/webapi/entry.cgi?api=SYNO.Foto.Thumbnail&method=get&version=2&id=1")

        assertFailsWith<IOException> { client.newCall(Request.Builder().url(url).build()).execute() }
    }

    @Test
    fun `an image passes through untouched`() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("JPEGBYTES").build())

        client.newCall(Request.Builder().url(server.url("/thumb")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("JPEGBYTES", response.body.string())
        }
    }
}
