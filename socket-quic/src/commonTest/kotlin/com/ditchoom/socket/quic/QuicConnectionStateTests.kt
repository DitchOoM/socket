package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuicConnectionStateTests {
    // --- State identity ---

    @Test
    fun idle_isSingleton() {
        assertIs<QuicConnectionState.Idle>(QuicConnectionState.Idle)
    }

    @Test
    fun handshaking_isSingleton() {
        assertIs<QuicConnectionState.Handshaking>(QuicConnectionState.Handshaking)
    }

    @Test
    fun draining_isSingleton() {
        assertIs<QuicConnectionState.Draining>(QuicConnectionState.Draining)
    }

    @Test
    fun established_carriesAlpn() {
        val state = QuicConnectionState.Established("h3")
        assertEquals("h3", state.negotiatedAlpn)
    }

    @Test
    fun established_differentAlpn() {
        val state = QuicConnectionState.Established("h3-29")
        assertEquals("h3-29", state.negotiatedAlpn)
    }

    // --- Closed state ---

    @Test
    fun gracefulAndUnspecified_areDifferentClosures() {
        // The distinction the old nullable could not express: both used to be `Closed(null)`.
        val graceful = QuicConnectionState.Closed(QuicCloseReason.Graceful)
        val unexplained = QuicConnectionState.Closed(QuicCloseReason.Unspecified)
        assertNotEquals(
            graceful,
            unexplained,
            "a graceful shutdown and an unexplained teardown must not be the same value — " +
                "collapsing them is what let a network-killed connection report itself as clean",
        )
    }

    @Test
    fun closed_recordsWhichSideClosed() {
        // Also discarded by the old bare-QuicError shape: a peer rejecting us and quiche aborting
        // locally arrived indistinguishable.
        val byPeer = QuicConnectionState.Closed(QuicCloseReason.ByPeer(QuicError.ProtocolViolation))
        val byLocal = QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.ProtocolViolation))
        assertNotEquals(byPeer, byLocal, "the same QuicError from opposite sides must remain distinguishable")
    }

    @Test
    fun deprecatedAccessors_stayBugCompatibleForExistingCallers() {
        // These shims deliberately preserve the OLD answers so a consumer on the deprecated API does
        // not silently acquire new behaviour. The truthful answer lives on `reason`.
        @Suppress("DEPRECATION")
        run {
            assertNull(QuicConnectionState.Closed(QuicCloseReason.Graceful).error)
            assertNull(QuicConnectionState.Closed(QuicCloseReason.Unspecified).error)
            assertTrue(QuicConnectionState.Closed(QuicCloseReason.Graceful).isCleanShutdown)
            assertTrue(
                QuicConnectionState.Closed(QuicCloseReason.Unspecified).isCleanShutdown,
                "bug-compatible on purpose: an unexplained teardown answered `true` under the old " +
                    "contract, and a deprecated accessor must not change its answer",
            )
            assertFalse(QuicConnectionState.Closed(QuicCloseReason.ByPeer(QuicError.ProtocolViolation)).isCleanShutdown)
            assertFalse(QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.CryptoError(40))).isCleanShutdown)
            assertEquals(
                QuicError.ConnectionRefused,
                QuicConnectionState.Closed(QuicCloseReason.ByPeer(QuicError.ConnectionRefused)).error,
            )
        }
    }

    @Test
    fun deprecatedConstructor_mapsOntoTheSealedReason() {
        @Suppress("DEPRECATION")
        run {
            assertEquals(QuicCloseReason.Graceful, QuicConnectionState.Closed(null).reason)
            assertEquals(
                QuicCloseReason.ByLocal(QuicError.ProtocolViolation),
                QuicConnectionState.Closed(QuicError.ProtocolViolation).reason,
            )
        }
    }

    // --- Exhaustive when coverage ---

    @Test
    fun allStates_areHandledExhaustively() {
        val states =
            listOf(
                QuicConnectionState.Idle,
                QuicConnectionState.Handshaking,
                QuicConnectionState.Established("h3"),
                QuicConnectionState.Draining,
                QuicConnectionState.Closed(QuicCloseReason.Graceful),
            )
        states.forEach { state ->
            // Exhaustive when — compiler enforces all branches
            val description =
                when (state) {
                    is QuicConnectionState.Idle -> "idle"
                    is QuicConnectionState.Handshaking -> "handshaking"
                    is QuicConnectionState.Established -> "established(${state.negotiatedAlpn})"
                    is QuicConnectionState.Draining -> "draining"
                    // Nested exhaustive when: the close reason is itself matched, not null-checked.
                    is QuicConnectionState.Closed ->
                        when (val r = state.reason) {
                            QuicCloseReason.Graceful -> "closed(graceful)"
                            QuicCloseReason.Unspecified -> "closed(unspecified)"
                            is QuicCloseReason.ByPeer -> "closed(peer=${r.error.describe()})"
                            is QuicCloseReason.ByLocal -> "closed(local=${r.error.describe()})"
                        }
                }
            assertTrue(description.isNotEmpty())
        }
    }
}
