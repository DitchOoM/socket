package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure, deterministic tests for the WebTransport ↔ HTTP/3 error-code mapping
 * (draft-ietf-webtrans-http3 §4.3) — no I/O, so they run on every platform (incl. jsNode).
 */
class WebTransportErrorCodeTests {
    @Test
    fun mapping_matchesTheDraftWorkedExamples() {
        // From the draft's worked table: n=0 → BASE, and the skip kicks in at every multiple of 0x1e.
        assertEquals(WebTransportWire.WT_ERROR_BASE, WebTransportWire.toHttp3ErrorCode(0))
        assertEquals(WebTransportWire.WT_ERROR_BASE + 1, WebTransportWire.toHttp3ErrorCode(1))
        // n=0x1d (last before the first skip): no skip yet.
        assertEquals(WebTransportWire.WT_ERROR_BASE + 0x1d, WebTransportWire.toHttp3ErrorCode(0x1d))
        // n=0x1e: floor(0x1e/0x1e)=1 → one value is skipped.
        assertEquals(WebTransportWire.WT_ERROR_BASE + 0x1e + 1, WebTransportWire.toHttp3ErrorCode(0x1e))
        // n=0x3b (0x1e..0x3b share skip=1); n=0x3c bumps skip to 2.
        assertEquals(WebTransportWire.WT_ERROR_BASE + 0x3b + 1, WebTransportWire.toHttp3ErrorCode(0x3b))
        assertEquals(WebTransportWire.WT_ERROR_BASE + 0x3c + 2, WebTransportWire.toHttp3ErrorCode(0x3c))
    }

    @Test
    fun roundTrip_acrossBoundariesAndFullU32Range() {
        val samples =
            listOf(
                0L,
                1L,
                0x1dL,
                0x1eL,
                0x1fL,
                0x3bL,
                0x3cL,
                0x3dL,
                30L,
                31L,
                60L,
                100L,
                1000L,
                65535L,
                0x7FFFFFFFL, // 2^31 - 1
                0x80000000L, // 2^31
                0xFFFFFFFEL,
                0xFFFFFFFFL, // 2^32 - 1, the max WebTransport application error code
            )
        for (n in samples) {
            val h3 = WebTransportWire.toHttp3ErrorCode(n)
            assertTrue(h3 >= WebTransportWire.WT_ERROR_BASE, "h3 code must land in the WT slice for n=$n")
            assertEquals(n, WebTransportWire.toWebTransportErrorCode(h3), "round-trip must recover n=$n")
        }
    }

    @Test
    fun roundTrip_isExhaustiveOverASlidingDenseWindow() {
        // Every value straddling several skip boundaries must round-trip exactly (no off-by-one).
        for (n in 0L..2000L) {
            assertEquals(n, WebTransportWire.toWebTransportErrorCode(WebTransportWire.toHttp3ErrorCode(n)))
        }
    }

    @Test
    fun skippedGreaseSlotsAreNeverProducedByTheForwardMap() {
        // The forward map must never emit a code whose offset-from-base is ≡ 0x1e (mod 0x1f): those are
        // the reserved slots the remapping exists to dodge.
        for (n in 0L..5000L) {
            val shifted = WebTransportWire.toHttp3ErrorCode(n) - WebTransportWire.WT_ERROR_BASE
            assertTrue(shifted % 0x1f != 0x1eL, "n=$n mapped onto a reserved GREASE slot")
        }
    }

    @Test
    fun upperBitsBeyondU32AreIgnored() {
        // The reset API carries a Long; only the low 32 bits are the WebTransport code (draft §4.3 domain).
        assertEquals(
            WebTransportWire.toHttp3ErrorCode(0x42),
            WebTransportWire.toHttp3ErrorCode(0xFFFF_FFFF_0000_0042uL.toLong()),
        )
    }
}
