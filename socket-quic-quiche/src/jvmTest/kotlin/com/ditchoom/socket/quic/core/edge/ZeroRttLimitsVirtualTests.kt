package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamLimit
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A server accepts 0-RTT only under the limits its ticket was issued with: one whose stream limits dropped since rejects
 * it, and the client then opens only what the server it reached grants; one whose limits are the same accepts it
 * (RFC 9000 section 7.4.1: a server that accepts 0-RTT must not reduce the limits the client remembers; RFC 9001
 * section 4.6.2: rejecting 0-RTT resets what the client assumed).
 */
class ZeroRttLimitsVirtualTests {
    private fun options(uni: Long) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            enableEarlyData = true,
            sessionStore = QuicSessionStore.NotKept,
            incomingStreams = IncomingStreams.Accept(unidirectional = QuicStreamLimit(uni.toUInt())),
        )

    private val tls = coreTlsConfig().copy(sessionTicketKeys = QuicSessionTicketKeys.Shared(keyMaterial()))

    @Test
    fun aServerWhoseStreamLimitsDroppedSinceItsTicketRejectsZeroRtt() {
        val outcome = resumedWithEarlyStreams(issuedUnder = 100, reachedUnder = 1)
        assertIs<QuicEarlyDataOutcome.NotAccepted>(assertIs<QuicResumptionOutcome.Resumed>(outcome).earlyData)
    }

    @Test
    fun aServerWhoseStreamLimitsAreTheSameAcceptsZeroRtt() {
        val outcome = resumedWithEarlyStreams(issuedUnder = 100, reachedUnder = 100)
        assertEquals(QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted), outcome)
    }

    /** A ticket from a server allowing [issuedUnder] unidirectional streams, resumed with an early one against [reachedUnder]. */
    private fun resumedWithEarlyStreams(
        issuedUnder: Long,
        reachedUnder: Long,
    ): QuicResumptionOutcome {
        var issued: QuicSessionTicket? = null
        VirtualPair.with(options(issuedUnder), tls = tls) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            issued = assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
        }
        lateinit var outcome: QuicResumptionOutcome
        VirtualPair.with(options(reachedUnder), tls = tls) { pair ->
            val resumption =
                QuicResumption.ResumeWithEarlyData(checkNotNull(issued)) {
                    val stream = openUniStream()
                    stream.writeFully(bytes(), 1.hours)
                    stream.shutdownSend()
                }
            val client = pair.reconnect(options(reachedUnder).copy(resumption = resumption))
            launch { runCatching { client.connection.runEarlyData(resumption, client.offer) { client.connection.established() } } }
            pair.runUntil(within = 5.seconds) { client.connection.state.value is QuicConnectionState.Established }
            outcome = client.connection.resumption
        }
        return outcome
    }

    private fun bytes() =
        BufferFactory.Default.allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private fun keyMaterial(): ReadBuffer {
        val key = BufferFactory.managed().allocate(QuicSessionTicketKeys.Shared.KEY_BYTES)
        repeat(QuicSessionTicketKeys.Shared.KEY_BYTES) { key.writeByte((it * 7 + 3).toByte()) }
        key.resetForRead()
        return key
    }

    private companion object {
        const val PAYLOAD = 600
    }
}
