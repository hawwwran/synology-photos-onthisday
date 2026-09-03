package com.hawwwran.photosonthisday.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateCheckerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var clock = 1_000_000L
    private lateinit var cacheFile: File

    @Before
    fun start() {
        server.start()
        cacheFile = File(folder.root, "update-check.json")
    }

    @After
    fun stop() = server.close()

    private fun checker() = UpdateChecker(cacheFile, "1.0.0", OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'), now = { clock })

    private val releases = """
        [
          {"tag_name":"v1.2.0-rc1","draft":false,"prerelease":true,"assets":[{"name":"OnThisDay-1.2.0-rc1.apk","browser_download_url":"https://x/rc.apk","size":5}]},
          {"tag_name":"v1.1.0","draft":false,"prerelease":false,"body":"Fixes.","assets":[
             {"name":"notes.txt","browser_download_url":"https://x/n.txt","size":1},
             {"name":"OnThisDay-1.1.0.apk","browser_download_url":"https://x/a.apk","size":24000000}
          ]}
        ]
    """.trimIndent()

    @Test
    fun `the newest stable release with an apk is found, with its size`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Last-Modified", "Wed").body(releases).build())

        val outcome = checker().check(force = true) as CheckOutcome.Found

        assertEquals("1.1.0", outcome.info.latestVersion)
        assertEquals("https://x/a.apk", outcome.info.apkUrl)
        assertEquals(24_000_000L, outcome.info.apkSize)
        assertEquals("Fixes.", outcome.info.releaseNotes)
        assertTrue(outcome.info.isNewer)
        assertTrue(cacheFile.exists())
    }

    @Test
    fun `within the day the cache answers without a request`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(releases).build())
        checker().check(force = true)
        clock += 60_000

        val outcome = checker().check(force = false)

        assertTrue(outcome is CheckOutcome.Found)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `offline with nothing cached is Unreachable, offline with a cache is the stale cache`() = runTest {
        val url = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(200).body(releases).build())
        checker().check(force = true)
        server.close()

        val stale = UpdateChecker(cacheFile, "1.0.0", OkHttpClient(), baseUrl = url, now = { clock }).check(force = true) as CheckOutcome.Found
        assertTrue(stale.info.stale)

        cacheFile.delete()
        val nothing = UpdateChecker(cacheFile, "1.0.0", OkHttpClient(), baseUrl = url, now = { clock }).check(force = true)
        assertEquals(CheckOutcome.Unreachable, nothing)
    }

    @Test
    fun `a page with no matching release is NoRelease`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""[{"tag_name":"docs-1","assets":[]}]""").build())

        assertEquals(CheckOutcome.NoRelease, checker().check(force = true))
    }
}
