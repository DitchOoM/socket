package com.ditchoom.socket.testkit.echo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the echo-corruption capture actually reports what it claims (#462).
 *
 * The instrument this replaces existed since #402 and was never exercised by a test — it was only ever
 * going to run at the moment of a real flake, which is precisely when you cannot afford to discover it
 * reports the wrong thing. Worse, it was private to one suite, so the one time the corruption struck a
 * release the artifact contained a stack trace and zero characters of evidence.
 *
 * So each case here feeds bytes whose *provenance is known by construction* and asserts the reading the
 * capture gives them. If these pass, a real occurrence produces a usable answer instead of a dump.
 */
class EchoCorruptionTests {
    @Test
    fun cleanBytesDecodeAndDoNotThrow() {
        assertEquals("hello", EchoCorruption.decodeOrFail("hello", "hello".encodeToByteArray(), chunks = 1))
    }

    /**
     * The cross-connection leak reading: the bytes ARE another live payload, whole.
     *
     * This is the one worth naming loudly, because it means a buffer reached the wrong reader rather
     * than being freed under someone — a different defect with a different fix from #415's.
     */
    @Test
    fun bytesThatAreAnotherInFlightPayloadAreNamedAsALeak() {
        val report =
            EchoCorruption.describeEchoCorruption(
                expected = "AAAA",
                received = "BBBB".encodeToByteArray(),
                chunks = 1,
                alsoInFlight = listOf("BBBB", "CCCC"),
            )
        assertContains(report, "ARE another in-flight payload")
        assertContains(report, "cross-connection")
    }

    /** The splice reading: another payload embedded inside what came back. */
    @Test
    fun bytesContainingAnotherInFlightPayloadAreNamedAsASplice() {
        val report =
            EchoCorruption.describeEchoCorruption(
                expected = "AAAAAAAA",
                received = "AABBBBAA".encodeToByteArray(),
                chunks = 2,
                alsoInFlight = listOf("BBBB"),
            )
        assertContains(report, "CONTAIN another in-flight payload")
        assertContains(report, "handed to two owners")
    }

    /**
     * The #415 reading: non-text noise, which is what a freed allocator chunk looks like.
     *
     * Uses bytes that are invalid UTF-8, so this also covers the path a real occurrence takes — the
     * decode throws, and the description has to be built anyway.
     */
    @Test
    fun nonTextNoiseIsNamedAsFreedChunkBytes() {
        val garbage = byteArrayOf(0x00, 0x7F.toByte(), 0xC3.toByte(), 0x28, 0x01, 0x02, 0x03, 0xFF.toByte())
        val failure =
            assertFailsWith<AssertionError> {
                EchoCorruption.decodeOrFail("AAAAAAAA", garbage, chunks = 1)
            }
        val report = failure.message.orEmpty()
        assertContains(report, "non-text noise")
        assertContains(report, "#415")
        // The evidence itself must survive, not just the reading.
        assertContains(report, "printable:")
        assertContains(report, "received :")
    }

    /** Nothing came back at all is a truncation, not corruption — and must not be misreported as one. */
    @Test
    fun anEmptyReadIsNamedATruncationNotCorruption() {
        val report = EchoCorruption.describeEchoCorruption("AAAA", ByteArray(0), chunks = 0)
        assertContains(report, "nothing came back at all")
    }

    /** The divergence index is what tells a reader where the good bytes stopped. */
    @Test
    fun theReportLocatesTheFirstDivergingByte() {
        val report =
            EchoCorruption.describeEchoCorruption(
                expected = "AAAAAA",
                received = "AAAZZZ".encodeToByteArray(),
                chunks = 1,
            )
        assertContains(report, "first divergence at index 3")
    }

    /** A caller with no knowledge of other payloads still gets a usable, non-misleading answer. */
    @Test
    fun printableBytesWithNoCandidatesAskForCandidatesRatherThanGuessing() {
        val report =
            EchoCorruption.describeEchoCorruption(
                expected = "AAAA",
                received = "ZZZZ".encodeToByteArray(),
                chunks = 1,
            )
        assertTrue(
            report.contains("alsoInFlight"),
            "with no candidate payloads the report must say what it needs to identify the source, " +
                "rather than asserting a root cause it cannot know:\n$report",
        )
    }
}
