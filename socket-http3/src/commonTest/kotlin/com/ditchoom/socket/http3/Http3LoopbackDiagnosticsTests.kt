package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The failure report is only worth shipping if it actually renders the thing it exists to surface, so
 * these assert on its OUTPUT rather than on the recording call succeeding.
 *
 * Context (#291): `productionServerRole_dynamicQpackRoundTrip` fails as
 * `QuicCloseException: connection closed [ApplicationError(applicationCode=514)]`. 514 is 0x202,
 * `QPACK_DECODER_STREAM_ERROR` (RFC 9204 §6) — which narrows the cause to one of four named violations
 * but never says WHICH, or with what operands. Those four are the difference between a hypothesis and
 * a fix, and the peer that raised one keeps it fully typed; only the observing peer is left with the
 * opaque code. These tests pin that a recorded violation reaches the report intact.
 */
class Http3LoopbackDiagnosticsTests {
    @Test
    fun report_namesTheTypedViolation_notJustTheOpaqueErrorCode() {
        val diagnostics = Http3LoopbackDiagnostics()
        diagnostics.recordStreamViolation(
            "S",
            Http3StreamException(Http3Violation.QpackSectionAckWithoutOutstanding(streamId = 4)),
        )

        val report = diagnostics.report(IllegalStateException("boom"))

        // The discriminator: which of the four, and its operand.
        assertContains(report, "QpackSectionAckWithoutOutstanding")
        assertContains(report, "streamId=4")
        // The human-readable form, so the report stands alone without the reader consulting the source.
        assertContains(report, "Section Acknowledgment for stream 4 with no outstanding section")
        // Rendered as the wire code the QUIC layer reports, so the report can be tied back to the
        // `ApplicationError(applicationCode=514)` the observing peer saw. 0x202 == 514.
        assertContains(report, "0x202")
        assertContains(report, "S stream-level H3 violation")
    }

    @Test
    fun report_carriesTheOperandOfAnInsertCountViolation() {
        val diagnostics = Http3LoopbackDiagnostics()
        diagnostics.recordStreamViolation(
            "C",
            Http3StreamException(Http3Violation.QpackInsertCountIncrementPastInserts(increment = 7)),
        )

        val report = diagnostics.report(IllegalStateException("boom"))

        assertContains(report, "QpackInsertCountIncrementPastInserts")
        // The increment is the evidence for an accounting bug — a violation name without it would still
        // leave the fix guessing.
        assertContains(report, "increment=7")
        assertContains(report, "0x202")
    }

    @Test
    fun report_withNoViolationRecorded_saysNothingAboutViolations() {
        // Guards against the report growing an empty "violation:" line that reads as a real finding on
        // every unrelated failure.
        val report = Http3LoopbackDiagnostics().report(IllegalStateException("boom"))

        assertFalse(report.contains("H3 violation"), "expected no violation section, got:\n$report")
    }
}
