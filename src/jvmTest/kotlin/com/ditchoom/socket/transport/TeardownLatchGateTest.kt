package com.ditchoom.socket.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fails the build when a class hand-rolls the teardown latch instead of using [TeardownOnce].
 *
 * ## Why a gate and not a code review
 *
 * This bug class has now been fixed four times in this package — #471 in [CodecConnection] and
 * [CodecSender], #473 in [ReconnectingConnection]'s collector guard, and again in that class's
 * `close()`, which kept the exact shape #471 had removed 21 minutes earlier. Every one of those
 * fixes was reviewed. Review is what let the fourth through: the reasoning lived in a commit message
 * and two files, and a commit message cannot fail a build.
 *
 * [TeardownOnce] made the right shape easy. This makes the wrong one loud. Neither alone is enough —
 * a primitive nobody is required to use is a suggestion.
 *
 * ## What it looks for
 *
 * Not "does this file mention `closed`" — plenty of correct code does. Two specific shapes:
 *
 *  1. **Check-then-act on a flag**: `if (closed) return` anywhere near `closed = true`. This is the
 *     literal defect, and `@Volatile` does not fix it: publication is not atomicity.
 *  2. **A hand-rolled latch pair**: `CompletableDeferred<Unit>()` used as teardown bookkeeping
 *     outside [TeardownOnce] itself. This shape is correct but should not be re-derived per class —
 *     re-deriving is how the `NonCancellable` hop or the loser's await gets forgotten.
 *
 * A genuine exception (see [CodecConnection.releaseResourcesOnce], which is deliberately neither
 * suspending nor awaiting) belongs in [ALLOWED] with a comment saying why, so the next person meets
 * the reasoning rather than the rule.
 */
class TeardownLatchGateTest {
    companion object {
        /**
         * Deliberate exceptions, each with its justification.
         *
         * `CodecConnection.releaseResourcesOnce` is a bare `CompletableDeferred.complete()` on
         * purpose: it is not suspending and its losers return rather than wait, because nothing
         * awaits a resource release the way a caller awaits `close()`. Routing it through
         * [TeardownOnce] would impose an await and a `NonCancellable` hop that shape does not want.
         */
        private val ALLOWED =
            mapOf(
                "CodecConnection.kt" to setOf("resourcesReleased"),
            )

        private const val TEARDOWN_ONCE = "TeardownOnce.kt"

        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""//[^\n]*""")

        /**
         * Strips comments before matching.
         *
         * Not an optimisation — a correctness requirement the first run of this gate found the hard
         * way. Every class that was *fixed* quotes the broken shape in its KDoc to explain what was
         * removed, so a scanner that reads prose convicts exactly the files that document the rule.
         * A gate that fires on its own documentation is a gate someone deletes.
         */
        fun code(text: String): String = text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
    }

    private fun commonMainRoot(): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/commonMain/kotlin")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        fail("could not locate src/commonMain/kotlin from ${File(".").absolutePath}")
    }

    @Test
    fun noClassHandRollsTheTeardownLatch() {
        val sources =
            commonMainRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != TEARDOWN_ONCE }
                .toList()
        check(sources.isNotEmpty()) { "found no sources to scan; the gate would pass vacuously" }

        val violations = sources.flatMap { violationsIn(it.name, it.readText()) }

        if (violations.isNotEmpty()) {
            fail(
                "teardown latches must go through TeardownOnce:\n\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    /** The detector, separated from the walk so [theGateCanSeeAHandRolledLatch] can exercise it. */
    private fun violationsIn(
        fileName: String,
        raw: String,
    ): List<String> {
        val text = code(raw)
        val allowed = ALLOWED[fileName].orEmpty()
        val violations = mutableListOf<String>()

        // 1. check-then-act on a flag
        if (Regex("""if \(closed\) return""").containsMatchIn(text) &&
            Regex("""\bclosed = true""").containsMatchIn(text)
        ) {
            violations +=
                "$fileName: `if (closed) return` with `closed = true` — check-then-act. " +
                "Two callers can both pass the guard and both run teardown. Use TeardownOnce."
        }

        // 2. a hand-rolled latch pair
        Regex("""val (\w+) = CompletableDeferred<Unit>\(\)""").findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name !in allowed && name.contains("teardown", ignoreCase = true)) {
                violations +=
                    "$fileName: `$name` re-derives TeardownOnce's latch by hand. Use " +
                    "TeardownOnce, or add it to ALLOWED with a comment saying why it differs."
            }
        }
        return violations
    }

    /**
     * Proves the gate above can still see the thing it exists to catch.
     *
     * A scanner that convicts nothing looks identical to a codebase with nothing to convict, and the
     * two are told apart only by feeding it something known-bad. `LincheckHarnessProbe` makes the
     * same argument for model checking; this is the same hazard in a cheaper tool.
     *
     * The second case is the one that actually bit: the first run of this gate flagged
     * `CodecConnection` and `ReconnectingConnection` — both already fixed — because each *quotes*
     * the broken shape in its KDoc to explain what was removed. Prose must not be evidence.
     */
    @Test
    fun theGateCanSeeAHandRolledLatch() {
        val handRolled =
            """
            class Leaky {
                @Volatile private var closed = false
                suspend fun close() {
                    if (closed) return
                    closed = true
                    resource.close()
                }
            }
            """.trimIndent()
        assertTrue(
            violationsIn("Leaky.kt", handRolled).isNotEmpty(),
            "the gate did not flag a verbatim check-then-act teardown guard, so a clean run over " +
                "the real sources proves nothing",
        )

        val documentedOnly =
            """
            /**
             * This class used to guard teardown with `if (closed) return` followed by
             * `closed = true`, which is check-then-act. It uses TeardownOnce now.
             */
            class Fixed {
                private val teardown = TeardownOnce()
                suspend fun close() = teardown.runOnce { resource.close() }
            }
            """.trimIndent()
        assertEquals(
            emptyList(),
            violationsIn("Fixed.kt", documentedOnly),
            "the gate convicted a fixed class for describing the defect it no longer has",
        )
    }
}
