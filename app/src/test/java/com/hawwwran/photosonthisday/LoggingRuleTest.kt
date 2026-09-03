package com.hawwwran.photosonthisday

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md: "Log the call name and the error code, nothing else." Album and sharing responses
 * carry live share passphrases, so nothing that could hold response content may reach a log.
 * Two mechanical checks over the source tree keep that true: only the named loggers call
 * `android.util.Log`, and none of their functions accepts free text (a `String`, `CharSequence`
 * or `Any` parameter), so every line they can write is built from a call name, a code, an enum, a
 * count or an exception's class name.
 */
class LoggingRuleTest {

    private val root = File("src/main/java/com/hawwwran/photosonthisday")

    /** The allowlist. Adding a logger means adding it here, and it must pass the second test. */
    private val loggers = setOf("api/ApiLog.kt", "data/IndexLog.kt", "update/UpdateLog.kt")

    private val logCall = Regex("""\bLog\.[dwiev]\(""")
    private val logImport = Regex("""^import android\.util\.Log\b""", RegexOption.MULTILINE)

    private fun sources(): List<Pair<String, String>> {
        assertTrue("run from the app module directory: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    @Test
    fun `only the named loggers call android util Log`() {
        val offenders = sources()
            .filter { (path, _) -> path !in loggers }
            .filter { (_, text) -> logCall.containsMatchIn(text) || logImport.containsMatchIn(text) }
            .map { it.first }
        assertTrue("these files log directly instead of through a logger: $offenders", offenders.isEmpty())
        val missing = loggers.filter { logger -> sources().none { it.first == logger } }
        assertTrue("allowlisted loggers that do not exist: $missing", missing.isEmpty())
    }

    @Test
    fun `no logger function takes free text`() {
        val freeText = Regex(""":\s*(String|CharSequence|Any)\??\s*(=|,|\))""")
        val declaration = Regex("""fun\s+\w+\s*\(([^)]*)\)""")
        for ((path, text) in sources().filter { it.first in loggers }) {
            for (match in declaration.findAll(text)) {
                val params = match.groupValues[1]
                assertTrue(
                    "$path: `${match.value}` takes free text; log a call, a code, an enum or a Throwable instead",
                    !freeText.containsMatchIn(params),
                )
            }
        }
    }
}
