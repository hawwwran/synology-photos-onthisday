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
    fun `a malformed answer says which one it was, and blames the address only on sign-in`() {
        val wrongHost = DsmErrorText.forFailure(ApiFailure.Malformed(Allowlist.API_INFO, "no success flag"))
        val proxyPage = DsmErrorText.forFailure(ApiFailure.Malformed(Allowlist.API_INFO, MalformedDetail.http(502)))
        val likesHttp = DsmErrorText.forFailure(ApiFailure.Malformed(Allowlist.FS_UPLOAD, MalformedDetail.http(404)))
        val unreadable = DsmErrorText.forFailure(ApiFailure.Malformed(Allowlist.FS_DOWNLOAD, MalformedDetail.UNREADABLE_LIKES_FILE))

        assertEquals("Adresa odpověděla, ale ne jako Synology NAS.", wrongHost)
        assertTrue(proxyPage, "502" in proxyPage)
        assertTrue(likesHttp, "404" in likesHttp && likesHttp.contains("složka", ignoreCase = true))
        assertTrue(likesHttp, "Synology NAS" !in likesHttp) // the address is fine; the folder is not

        // Seen on a second household account, 2026-09-04: the proxy in front of DSM, not a permission.
        val gateway = DsmErrorText.forFailure(ApiFailure.Malformed(Allowlist.FS_UPLOAD, MalformedDetail.http(502)))
        assertTrue(gateway, "502" in gateway && gateway.contains("proxy", ignoreCase = true))
        assertTrue(gateway, !gateway.contains("oprávnění", ignoreCase = true) || gateway.contains("ne v oprávněních"))
        val denied = DsmErrorText.forFailure(ApiFailure.Malformed(Allowlist.FS_DOWNLOAD, MalformedDetail.http(403)))
        assertTrue(denied, denied.contains("File Station"))
        assertTrue(unreadable, unreadable.contains("likes.json"))
        assertTrue(unreadable, unreadable.contains("nebyl přepsán", ignoreCase = true))
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
        assertTrue(DsmErrorText.forFailure(ApiFailure.Malformed(call, MalformedDetail.http(502))).isNotBlank())
    }
}
