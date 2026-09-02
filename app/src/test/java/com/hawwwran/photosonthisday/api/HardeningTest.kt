package com.hawwwran.photosonthisday.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan.md §2 and decision 008, turned into checks that fail when a safety rule is broken. */
class HardeningTest {

    private val writeWords = listOf(
        "add", "create", "set", "update", "delete", "remove", "rename", "move",
        "upload", "share", "edit", "copy", "apply", "save", "modify", "import",
    )

    /** "Synology Photos is read-only." Every read is a read verb; the read set is all reads. */
    @Test
    fun `the read allowlist holds only read methods`() {
        val readMethods = setOf("query", "login", "logout", "get", "list", "count", "download")
        for (call in Allowlist.reads) {
            assertTrue("${call.name} is not a known read method", call.method in readMethods)
        }
    }

    /** No read names a mutation, whatever HTTP verb it uses; no read touches a non-Photos write api. */
    @Test
    fun `no read allowlist entry implies a write`() {
        for (call in Allowlist.reads) {
            val method = call.method.lowercase()
            for (word in writeWords) {
                assertFalse("${call.name} looks like a write ($word)", method.contains(word))
            }
        }
    }

    /**
     * Decision 008: the only write is saving the app's own likes file over File Station. It is not
     * a Photos endpoint, and it is not destructive (no delete, rename, move or copy).
     */
    @Test
    fun `the write allowlist is only the likes-file upload`() {
        assertEquals(setOf(Allowlist.FS_UPLOAD), Allowlist.writes)
        for (call in Allowlist.writes) {
            assertTrue("a write must be File Station, not Photos", call.api.startsWith("SYNO.FileStation."))
            assertFalse("a write into Photos is forbidden", call.api.startsWith("SYNO.Foto"))
            for (destructive in listOf("delete", "remove", "rename", "move", "copy")) {
                assertFalse("${call.name} is destructive", call.method.lowercase().contains(destructive))
            }
        }
    }

    /**
     * "Never log a response body." The failures a caller can log carry only the call name and a
     * code, never response content. [ApiLog] is the only logger and takes the same safe inputs.
     */
    @Test
    fun `failure messages carry only the call and a code`() {
        val call = Allowlist.itemList(Space.PERSONAL)
        val secret = "passphrase=hunter2 sharing_link=https://x.quickconnect.to/mo/sharing/SECRET"

        val dsm = ApiFailure.DsmError(call, 120).message!!
        val expired = ApiFailure.SessionExpired(call, 106).message!!
        val malformed = ApiFailure.Malformed(call, "response is not JSON").message!!

        for (message in listOf(dsm, expired, malformed)) {
            assertTrue("message should name the call", message.contains(call.name))
            assertFalse("message must not carry a body", message.contains(secret))
            assertFalse(message.contains("passphrase"))
            assertFalse(message.contains("sharing_link"))
        }
        assertTrue(dsm.contains("120"))
        assertTrue(expired.contains("106"))
    }
}
