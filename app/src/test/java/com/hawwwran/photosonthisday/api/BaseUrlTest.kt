package com.hawwwran.photosonthisday.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlTest {

    private fun ok(text: String): String = (parseBaseUrl(text) as BaseUrlResult.Ok).url.toString()

    private fun refused(text: String): String = (parseBaseUrl(text) as BaseUrlResult.Refused).reason

    @Test
    fun `https addresses pass, with or without a port and trailing slash`() {
        assertEquals("https://nas.example.com/", ok("https://nas.example.com"))
        assertEquals("https://nas.example.com:5001/", ok("https://nas.example.com:5001/"))
        assertEquals("https://nas.example.com/", ok("  https://nas.example.com//  "))
    }

    @Test
    fun `a bare host is read as https`() {
        assertEquals("https://nas.example.com/", ok("nas.example.com"))
    }

    @Test
    fun `http is refused with the reason`() {
        val reason = refused("http://nas.example.com:5000")
        assertTrue(reason, "https://" in reason)
        assertTrue(reason, "password" in reason)
    }

    @Test
    fun `empty and nonsense are refused`() {
        assertTrue(refused("   ").isNotBlank())
        assertTrue(refused("ht!tp://x y").isNotBlank())
    }
}
