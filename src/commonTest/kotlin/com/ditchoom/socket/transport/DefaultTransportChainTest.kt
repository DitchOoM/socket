package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig
import kotlin.test.Test
import kotlin.test.assertEquals

private class NamedTransport(
    private val name: String,
) : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream = throw UnsupportedOperationException("never connected")

    override fun toString() = name
}

/**
 * The platform-filter test (RFC §10.7): one global ranking, absent rungs dropped. Axis A is a
 * compile-time fact — a platform simply cannot construct the transports its source set lacks — so the
 * filter is exercised by leaving slots null, exactly as each platform's wiring does.
 */
class DefaultTransportChainTest {
    private val quic = NamedTransport("quic")
    private val webTransport = NamedTransport("webTransport")
    private val tcp = NamedTransport("tcp")
    private val webSocket = NamedTransport("webSocket")

    @Test
    fun fullSetYieldsTheGlobalRanking() {
        val chain = defaultTransportChain(TransportSet(quic = quic, webTransport = webTransport, tcp = tcp, webSocket = webSocket))
        assertEquals(listOf<Transport>(quic, webTransport, tcp, webSocket), chain)
    }

    @Test
    fun webSetYieldsTheTwoRungChain() {
        // Browser: no raw QUIC, no raw TCP — WebTransport → WebSocket (§2.1, resolved §12 question).
        val chain = defaultTransportChain(TransportSet(webTransport = webTransport, webSocket = webSocket))
        assertEquals(listOf<Transport>(webTransport, webSocket), chain)
    }

    @Test
    fun rankingIsFixedRegardlessOfWhichRungsExist() {
        val chain = defaultTransportChain(TransportSet(tcp = tcp, quic = quic))
        assertEquals(listOf<Transport>(quic, tcp), chain, "order comes from the global ranking, not argument order")
    }
}
