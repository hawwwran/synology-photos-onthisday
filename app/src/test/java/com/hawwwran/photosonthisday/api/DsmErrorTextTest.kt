package com.hawwwran.photosonthisday.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmErrorTextTest {

    @Test
    fun `login texts carry the raw code so the user sees what DSM said`() {
        for (code in listOf(400, 403, 404, 407, 999)) {
            val text = DsmErrorText.forLogin(code)
            assertTrue(text, "(chyba DSM $code)" in text)
        }
    }

    @Test
    fun `auto-block is named as such`() {
        assertTrue(DsmErrorText.forLogin(407).contains("blokov", ignoreCase = true))
    }

    /** 407 is auto-block on a login and "not permitted" in File Station: the api decides the text. */
    @Test
    fun `the same code reads differently per api`() {
        val login = DsmErrorText.forFailure(ApiFailure.DsmError(Allowlist.LOGIN, 407))
        val upload = DsmErrorText.forFailure(ApiFailure.DsmError(Allowlist.FS_UPLOAD, 407))

        assertTrue(login, login.contains("blokov", ignoreCase = true))
        assertTrue(upload, upload.contains("zápis", ignoreCase = true))
        assertTrue(upload.contains("407"))
    }

    @Test
    fun `the File Station refusals a household meets name the setting to change`() {
        val notPermitted = DsmErrorText.forFailure(ApiFailure.DsmError(Allowlist.FS_DOWNLOAD, 105))
        val noFolder = DsmErrorText.forFailure(ApiFailure.DsmError(Allowlist.FS_UPLOAD, 408))

        assertTrue(notPermitted, notPermitted.contains("File Station"))
        assertTrue(noFolder, noFolder.contains("domovské složky", ignoreCase = true))
        for (text in listOf(notPermitted, noFolder)) assertTrue(text, "chyba DSM" in text)
    }

    @Test
    fun `a Photos code keeps the common text`() {
        val photos = DsmErrorText.forFailure(ApiFailure.DsmError(Allowlist.itemList(Space.PERSONAL), 105))

        assertTrue(photos, photos.contains("oprávnění"))
        assertTrue(photos, "File Station" !in photos)
    }

    @Test
    fun `failures without a code say what kind they were`() {
        val call = Allowlist.API_INFO
        assertEquals(
            "NAS není dostupný. Zkontrolujte připojení k internetu.",
            DsmErrorText.forFailure(ApiFailure.Transport(call, java.io.IOException("x"))),
        )
        assertTrue(DsmErrorText.forFailure(ApiFailure.SessionExpired(call, 119)).contains("119"))
        assertTrue(DsmErrorText.forFailure(ApiFailure.DsmError(call, 120)).contains("120"))
        assertTrue(DsmErrorText.forFailure(ApiFailure.Malformed(call, "HTTP 502")).isNotBlank())
    }
}
