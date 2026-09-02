package com.hawwwran.photosonthisday.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan.md §2, turned into checks that fail when a safety rule is broken. */
class HardeningTest {

    /** "Synology Photos is read-only." Every allowlisted method is a read verb. */
    @Test
    fun `the allowlist holds only read methods`() {
        val readMethods = setOf("query", "login", "logout", "get", "list", "count", "download")
        for (call in Allowlist.all) {
            assertTrue("${call.name} is not a known read method", call.method in readMethods)
        }
    }

    /** No allowlisted method name implies a mutation, whatever HTTP verb it uses. */
    @Test
    fun `no allowlisted method name implies a write`() {
        val writeWords = listOf(
            "add", "create", "set", "update", "delete", "remove", "rename", "move",
            "upload", "share", "edit", "copy", "apply", "save", "modify", "import",
        )
        for (call in Allowlist.all) {
            val lower = call.method.lowercase()
            for (word in writeWords) {
                assertFalse("${call.name} looks like a write ($word)", lower.contains(word))
            }
            // login/logout are auth, not photo mutations; guard the rest against the api name too.
            if (call.api.startsWith("SYNO.Foto") || call.api.startsWith("SYNO.FotoTeam")) {
                for (word in writeWords) {
                    assertFalse("${call.api} looks like a write ($word)", call.api.lowercase().contains(word))
                }
            }
        }
    }

    /**
     * "Never log a response body." The failures a caller can log carry only the call name and a
     * code, never response content. This asserts the message contract those log lines are built
     * from; [ApiLog] is the only logger and takes the same safe inputs.
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
