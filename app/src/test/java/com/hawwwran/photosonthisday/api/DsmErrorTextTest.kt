package com.hawwwran.photosonthisday.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmErrorTextTest {

    @Test
    fun `login texts carry the raw code so the user sees what DSM said`() {
        for (code in listOf(400, 403, 404, 407, 999)) {
            val text = DsmErrorText.forLogin(code)
            assertTrue(text, "(DSM error $code)" in text)
        }
    }

    @Test
    fun `auto-block is named as such`() {
        assertTrue(DsmErrorText.forLogin(407).contains("blocked", ignoreCase = true))
    }

    @Test
    fun `failures without a code say what kind they were`() {
        val call = Allowlist.API_INFO
        assertEquals(
            "The NAS could not be reached. Check the address and the connection.",
            DsmErrorText.forFailure(ApiFailure.Transport(call, java.io.IOException("x"))),
        )
        assertTrue(DsmErrorText.forFailure(ApiFailure.SessionExpired(call, 119)).contains("119"))
        assertTrue(DsmErrorText.forFailure(ApiFailure.DsmError(call, 120)).contains("120"))
        assertTrue(DsmErrorText.forFailure(ApiFailure.Malformed(call, "HTTP 502")).isNotBlank())
    }
}
