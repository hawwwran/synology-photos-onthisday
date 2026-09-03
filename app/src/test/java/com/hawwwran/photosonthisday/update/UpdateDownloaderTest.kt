package com.hawwwran.photosonthisday.update

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var wakeLocks = 0
    private val downloader by lazy {
        UpdateDownloader(
            cacheDir = folder.root,
            wakeLockFactory = { wakeLocks++; UpdateDownloader.WakeLockHolder { wakeLocks-- } },
            appClient = OkHttpClient.Builder().followRedirects(false).build(),
        )
    }

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `a complete target of the expected size is reused without a request`() = runTest {
        val target = File(folder.root, "updates/OnThisDay-1.1.0.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }

        val events = downloader.download(server.url("/a.apk").toString(), "1.1.0", expectedSize = 10).toList()

        assertEquals(UpdateDownloader.DownloadProgress.Done(target), events.last())
        assertEquals(0, server.requestCount)
        assertEquals("no wake lock for a reuse", 0, wakeLocks)
    }

    @Test
    fun `a target of another size is downloaded again, following GitHub's redirect`() = runTest {
        File(folder.root, "updates/OnThisDay-1.1.0.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)) }
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/asset").toString()).build())
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())

        val events = downloader.download(server.url("/a.apk").toString(), "1.1.0", expectedSize = 10).toList()

        val done = events.last() as UpdateDownloader.DownloadProgress.Done
        assertEquals(10L, done.file.length())
        assertEquals(2, server.requestCount)
        assertEquals("released", 0, wakeLocks)
    }

    @Test
    fun `a stream that breaks fails and leaves no file`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("x".repeat(256 * 1024))
                .onResponseBody(SocketEffect.CloseSocket(closeSocket = true))
                .build(),
        )

        val events = downloader.download(server.url("/a.apk").toString(), "1.2.0", expectedSize = 0).toList()

        val failed = events.filterIsInstance<UpdateDownloader.DownloadProgress.Failed>()
        assertTrue(failed.isNotEmpty())
        assertTrue(File(folder.root, "updates").listFiles().orEmpty().none { it.name.startsWith("OnThisDay-1.2.0") })
    }
}
