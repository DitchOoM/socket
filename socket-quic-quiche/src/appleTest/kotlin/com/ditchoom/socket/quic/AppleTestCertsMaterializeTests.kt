@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concurrent test processes materialize the same `$TMPDIR/aqe-<name>`. These pin what a reader of that
 * path can observe while a writer is part-way through, with the writer's pause made deterministic by
 * [AppleTestCerts.materialize]'s `whileWriting` hook (the instant a truncate-then-write file is partial).
 */
class AppleTestCertsMaterializeTests {
    private val text = "-----BEGIN TEST-----\n" + "A".repeat(2000) + "\n-----END TEST-----\n"

    private fun path(tag: String) =
        "${platform.Foundation.NSTemporaryDirectory().trimEnd(
            '/',
        )}/aqe-materialize-$tag-${platform.Foundation.NSProcessInfo.processInfo.globallyUniqueString}"

    /** What a reader sees at [path] right now: nothing, or the file's text. */
    private sealed interface Seen {
        data object Missing : Seen

        data class Text(
            val value: String,
        ) : Seen
    }

    private fun see(path: String): Seen = if (access(path, F_OK) != 0) Seen.Missing else Seen.Text(AppleTestCerts.readText(path))

    private fun assertNeverPartial(
        observed: List<Seen>,
        expectedWhole: String,
    ) {
        assertTrue(observed.isNotEmpty(), "the reader never ran")
        observed.forEach {
            when (it) {
                Seen.Missing -> Unit
                is Seen.Text ->
                    assertEquals(
                        expectedWhole,
                        it.value,
                        "a reader saw a partial file: ${it.value.length} of ${expectedWhole.length} bytes",
                    )
            }
        }
    }

    @Test
    fun aReaderDuringAWriteSeesNothingOrTheWholeFile() {
        val path = path("one")
        val observed = mutableListOf<Seen>()
        try {
            AppleTestCerts.materialize(path, text, whileWriting = { observed += see(path) })
            assertEquals(Seen.Text(text), see(path))
        } finally {
            unlink(path)
        }
        assertNeverPartial(observed, text)
    }

    @Test
    fun aSecondWriterRunningInsideTheFirstNeverExposesAPartialFile() {
        val path = path("two")
        val observed = mutableListOf<Seen>()
        try {
            AppleTestCerts.materialize(
                path,
                text,
                whileWriting = {
                    // A second process writing the same path, then a reader looking, while the first is paused.
                    AppleTestCerts.materialize(path, text, whileWriting = { observed += see(path) })
                    observed += see(path)
                },
            )
            assertEquals(Seen.Text(text), see(path))
        } finally {
            unlink(path)
        }
        assertNeverPartial(observed, text)
    }

    @Test
    fun noSiblingFilesAreLeftBehind() {
        val path = path("clean")
        try {
            AppleTestCerts.materialize(path, text)
            AppleTestCerts.materialize(path, text)
            val dir = path.substringBeforeLast('/')
            val leftovers = NativeTestFiles.filesIn(dir).keys.filter { it.startsWith(path.substringAfterLast('/') + ".") }
            assertEquals(emptyList(), leftovers)
        } finally {
            unlink(path)
        }
    }
}
