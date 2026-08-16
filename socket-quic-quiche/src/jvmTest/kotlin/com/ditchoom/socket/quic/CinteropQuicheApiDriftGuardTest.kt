package com.ditchoom.socket.quic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard for the one duplicated FFI surface in this repository.
 *
 * `CinteropQuicheApi` exists twice — `src/linuxMain` and `src/appleMain` — because the Apple copy was
 * taken near-verbatim from the Linux one during the quiche-on-Apple pivot and kept separate so the
 * (then unvalidatable) Linux build was not disturbed. Its own KDoc calls the dedup "a follow-up".
 *
 * ## Why a test and not a TODO
 *
 * A duplicated FFI binding is not a stylistic problem, it is a correctness one with an ongoing cost:
 * a fix applied to one copy silently leaves the other wrong, and both copies bind the *same*
 * `Quiche.def` against the *same* quiche version, so nothing about the platform makes them
 * legitimately diverge. #345 named this as the one item on its list with continuing cost rather than
 * absent capability, and asked for either a dedup or "at minimum adding a guard that fails when the
 * two drift". This is that guard.
 *
 * Measured when written: 957 vs 949 lines, a 25-line diff, and **identical** `quiche_*` symbol sets
 * (83 each, none unique to either side). The two files are the same program apart from a header and
 * one line of sockaddr decoding, so a strict comparison is both possible and cheap.
 *
 * ## What is deliberately allowed to differ
 *
 * Exactly two things, both enumerated below as [ALLOWED_DIFFERENCES]:
 *
 *  - the class KDoc, which names its platform, and
 *  - the address-family read in the sockaddr decode. Darwin's `sockaddr` has `sa_len` (uint8) at
 *    byte 0 and `sa_family` (uint8) at byte 1; Linux has no `sa_len` and stores `sa_family` as a
 *    uint16 LE at byte 0. Port and address offsets are identical on both, so only that one read
 *    differs.
 *
 * Anything else that diverges fails this test. Widening the allowlist is then a deliberate,
 * reviewable act rather than a silent drift — which is the entire point.
 *
 * ## What this does NOT do
 *
 * It does not dedup them, and it is not a substitute for doing so. It converts "we will find out the
 * next time someone reads both files" into "CI says so on the commit that caused it". Comment text
 * is ignored, so a comment fix on one side alone will not fail — only code drifts.
 *
 * JVM-only because it reads repository sources rather than exercising either binding; guarding it
 * once covers both native consumers. Same idiom as [LocalhostCertFixtureGuardTest].
 */
class CinteropQuicheApiDriftGuardTest {
    private companion object {
        const val REL_LINUX = "src/linuxMain/kotlin/com/ditchoom/socket/quic/CinteropQuicheApi.kt"
        const val REL_APPLE = "src/appleMain/kotlin/com/ditchoom/socket/quic/CinteropQuicheApi.kt"

        /**
         * Linux line → Apple line, for the code that is *supposed* to differ.
         *
         * One entry. If a second is ever needed, the reviewer should ask why the copies are drifting
         * rather than reach for this map.
         */
        val ALLOWED_DIFFERENCES: Map<String, String> =
            mapOf(
                // Darwin reads sa_family as a uint8 at byte 1; Linux as a uint16 LE at byte 0.
                "when (u8(addr, 0) or (u8(addr, 1) shl 8)) {" to "when (u8(addr, 1)) {",
            )
    }

    /**
     * Gradle runs tests with the module directory as the working directory, but a run rooted at the
     * repository is common enough (IDE configurations, `--project-dir`) to be worth surviving.
     */
    private fun source(relative: String): File =
        listOf(File(relative), File("socket-quic-quiche/$relative"))
            .firstOrNull { it.isFile }
            ?: error("Cannot find $relative from ${File(".").absolutePath} — has the file moved?")

    /**
     * Code lines only: comments and blanks dropped, indentation normalised.
     *
     * Comments are excluded on purpose. The two copies carry different prose about their platforms
     * and always will, and a guard that failed on a typo fix would be turned off rather than obeyed.
     * What must not drift is the code.
     */
    private fun codeLines(file: File): List<String> =
        file
            .readLines()
            .map { it.trim() }
            .filterNot { line ->
                line.isEmpty() ||
                    line.startsWith("//") ||
                    line.startsWith("*") ||
                    line.startsWith("/*")
            }

    @Test
    fun appleAndLinuxBindTheSameQuicheSymbols() {
        // Code lines only, NOT readText(): the two copies' KDoc mentions `quiche_*` names in prose,
        // and one file gaining a sentence must not read as gaining a binding.
        val symbolsOf = { f: File ->
            Regex("quiche_[a-z0-9_]+").findAll(codeLines(f).joinToString("\n")).map { it.value }.toSortedSet()
        }
        val linux = symbolsOf(source(REL_LINUX))
        val apple = symbolsOf(source(REL_APPLE))

        assertTrue(linux.isNotEmpty(), "Found no quiche_* symbols in the Linux binding — has the file moved?")
        assertEquals(
            linux,
            apple,
            "The Apple and Linux CinteropQuicheApi bind different quiche_* symbols.\n" +
                "  only in linuxMain: ${linux - apple}\n" +
                "  only in appleMain: ${apple - linux}\n" +
                "Both bind the same Quiche.def against the same quiche version, so this is a fix " +
                "applied to one copy and not the other (#345 item 7). Apply it to both, or dedup them.",
        )
    }

    @Test
    fun appleAndLinuxCinteropBodiesAgreeLineForLine() {
        val linux = codeLines(source(REL_LINUX))
        val apple = codeLines(source(REL_APPLE))

        val drift =
            linux
                .zip(apple)
                .withIndex()
                .filter { (_, pair) ->
                    val (l, a) = pair
                    l != a && ALLOWED_DIFFERENCES[l] != a
                }.map { (i, pair) -> "  [$i] linux: ${pair.first}\n      apple: ${pair.second}" }

        assertTrue(
            drift.isEmpty(),
            "Apple and Linux CinteropQuicheApi have drifted in ${drift.size} code line(s):\n" +
                drift.joinToString("\n") + "\n\n" +
                "These two files are the same program apart from the sockaddr family read. A change " +
                "to one is a change owed to the other (#345 item 7). If the difference is genuinely " +
                "platform-required, add it to ALLOWED_DIFFERENCES with a comment saying why.",
        )
        assertEquals(
            linux.size,
            apple.size,
            "Apple and Linux CinteropQuicheApi have different code-line counts " +
                "(linux=${linux.size}, apple=${apple.size}), so one copy gained or lost code the " +
                "other did not. The line-by-line comparison above only covers the shared prefix.",
        )
    }
}
