package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.nativeMemoryAccess
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `socket-handshake-pto-ceiling` quiche patch, against the real library on quiche's own virtual clock: the initial
 * RTT sets the first PTO, a ceiling caps every PTO interval while the handshake is unconfirmed and re-arms the timer
 * when it is set, and removing it gives quiche's own PTO back. No socket and no peer: a client's first flight arms
 * the timer.
 */
class HandshakePtoCeilingQuicheTests {
    private val ms = 1_000_000L
    private val api = loadQuicheApi()
    private val factory = BufferFactory.network()

    /** A client connection whose first flight left at virtual time [T0], with quiche's PTO armed. */
    private fun firstFlight(initialRttNanos: Long?): QuicheConn {
        val config = api.configNew(1)
        val alpn = encodeAlpnList(listOf("test"), factory)
        api.configSetApplicationProtos(config, alpn.nativeMemoryAccess!!.nativeAddress, alpn.remaining())
        api.configSetMaxIdleTimeout(config, 30_000L)
        if (initialRttNanos != null) api.configSetInitialRtt(config, initialRttNanos)
        val name =
            factory.allocate(10).apply {
                writeString("localhost", Charset.UTF8)
                writeByte(0)
                resetForRead()
            }
        val scid = generateScid(factory, Random(7))
        val peer = InetSocketAddress("127.0.0.1", 4433).toNativeSockAddr(factory)
        val local = InetSocketAddress("127.0.0.1", 55555).toNativeSockAddr(factory)
        api.setThreadVirtualTimeNanos(T0)
        val conn =
            api.connect(
                name.nativeMemoryAccess!!.nativeAddress,
                9,
                scid.nativeMemoryAccess!!.nativeAddress,
                QUIC_ISSUED_CONN_ID_LEN,
                local.address,
                local.length,
                peer.address,
                peer.length,
                config,
            )
        api.configFree(config)
        val out = factory.allocate(1350)
        val sendInfo = api.sendInfoNew()
        while (api.connSend(conn, out.nativeMemoryAccess!!.nativeAddress, 1350, sendInfo) > 0) Unit
        api.sendInfoFree(sendInfo)
        return conn
    }

    private fun waitNanos(conn: QuicheConn): Long = api.connTimeoutNanos(conn)

    private fun run(test: () -> Unit) {
        try {
            test()
        } finally {
            api.clearThreadVirtualTime()
        }
    }

    @Test
    fun theInitialRttSetsTheFirstPto() =
        run {
            assertEquals(999 * ms, waitNanos(firstFlight(initialRttNanos = null)), "RFC 9002: 333 ms + 4 × 166.5 ms")
            assertEquals(240 * ms, waitNanos(firstFlight(initialRttNanos = 80 * ms)), "80 ms + 4 × 40 ms")
        }

    @Test
    fun aCeilingCapsThePtoAtOnceAndRemovingItGivesQuichesOwnBack() =
        run {
            val conn = firstFlight(initialRttNanos = null)
            api.connSetHandshakePtoCeiling(conn, PtoCeiling.At(50 * ms))
            assertEquals(50 * ms, waitNanos(conn), "setting the ceiling re-arms the armed PTO")
            api.connSetHandshakePtoCeiling(conn, PtoCeiling.None)
            assertEquals(999 * ms, waitNanos(conn))
        }

    /** quiche doubles its PTO per expiry; the ceiling holds every backed-off interval while unconfirmed. */
    @Test
    fun aBackedOffPtoIsHeldToTheCeiling() =
        run {
            val conn = firstFlight(initialRttNanos = null)
            api.connSetHandshakePtoCeiling(conn, PtoCeiling.At(100 * ms))
            var now = T0
            repeat(3) {
                now += waitNanos(conn)
                api.setThreadVirtualTimeNanos(now)
                api.connOnTimeout(conn)
                val out = factory.allocate(1350)
                val sendInfo = api.sendInfoNew()
                var probes = 0
                while (api.connSend(conn, out.nativeMemoryAccess!!.nativeAddress, 1350, sendInfo) > 0) probes++
                api.sendInfoFree(sendInfo)
                assertTrue(probes > 0, "the PTO sent a probe")
                assertTrue(waitNanos(conn) <= 100 * ms, "expiry ${it + 1}: ${waitNanos(conn)} ns, over the ceiling")
            }
        }

    private companion object {
        const val T0 = 100_000_000_000L
    }
}
