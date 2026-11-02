package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A handshake that completes and fails in the same step is never reported as established.
 *
 * quiche sets `handshake_completed` before it validates the peer's transport parameters
 * (`do_handshake` → `parse_peer_transport_params`), so a server whose parameters fail validation —
 * an `initial_source_connection_id` that does not match the SCID the client adopted, for one —
 * leaves the connection reading `is_established() && is_closed()`, with TRANSPORT_PARAMETER_ERROR as
 * its local error and none of the peer's limits applied. Published as Established, the caller's first
 * `openStream()` was refused with a stream-limit error (the peer's stream limit was never applied, so
 * it is 0) and the real reason was lost. The quic-interop-runner caught this against msquic under
 * handshake loss and corruption.
 */
class HandshakeClosedOnCompletionTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun aHandshakeThatClosesAsItCompletesGoesStraightToClosed() =
        runTest {
            val api = StubQuicheApi()
            val failure = QuicError.TransportParameterError("initial_source_connection_id mismatch")
            api.established = true
            api.closed = true
            api.localError = failure

            val driver = createTestDriver(api)
            // Unconfined: the collector runs inside each assignment, so a state published and replaced
            // within one driver wake is still seen.
            val seen = mutableListOf<QuicConnectionState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.state.collect { seen += it } }
            driver.start(this)
            try {
                runCurrent()
                assertTrue(
                    seen.none { it is QuicConnectionState.Established },
                    "a connection quiche closed while completing its handshake was published as Established: $seen",
                )
                val state = driver.state.value
                assertIs<QuicConnectionState.Closed>(state, "states seen: $seen")
                assertEquals(QuicCloseReason.ByLocal(failure), state.reason)
            } finally {
                driver.commands.close()
            }
        }

    private fun createTestDriver(api: QuicheApi): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
            clock = RealDriverClock,
            driverContext = EmptyCoroutineContext,
        )
}
