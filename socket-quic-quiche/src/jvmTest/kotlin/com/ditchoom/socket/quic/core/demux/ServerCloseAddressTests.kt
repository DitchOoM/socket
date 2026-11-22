package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A server's close toward a client whose address it has not validated (RFC 9000 §8.1): it is never repeated unprompted,
 * and late packets are answered only within three times what they brought. A close before the handshake is confirmed
 * is answered with every datagram it went out in. The test thread is the loop; virtual time.
 */
class ServerCloseAddressTests {
    @Test
    fun aCloseBeforeTheClientsAddressIsValidatedIsNeverRepeatedUnprompted() {
        val api = stub().apply { established = false }
        val rig = closedAtZero(api, seed = 7)
        for (at in listOf(PTO, PTO + 1, 2 * PTO, 2 * PTO + 1, DRAIN - 1)) rig.pass(at)
        assertEquals(1, rig.io.sent.size, "the close alone: nothing unprompted toward an unvalidated address")
        rig.pass(DRAIN + 1)
        assertIs<Life.Ended>(rig.conns.single().life())
    }

    @Test
    fun aCloseAfterConfirmationIsRepeatedOneAndTwoProbeTimeoutsAfterIt() {
        val rig = closedAtZero(stub(), seed = 8)
        for (at in listOf(PTO, PTO + 1, 2 * PTO, 2 * PTO + 1, DRAIN - 1)) rig.pass(at)
        assertEquals(3, rig.io.sent.size, "the close and its two repeats toward a validated address")
    }

    @Test
    fun aLatePacketTowardAnUnvalidatedAddressIsAnsweredOnlyWithinThreeTimesItsSize() {
        val api = stub().apply { established = false }
        val rig = closedAtZero(api, seed = 9)
        val dcid = rig.dcid
        // 3 × 100 bytes cannot cover the 1,300-byte close.
        rig.io.arrive(initial(dcid, size = SMALL), peer(1))
        rig.pass(1)
        assertEquals(1, rig.io.sent.size, "a late packet too small to cover the answer draws none")
        // The second late packet is the next doubling: 3 × (100 + 1,200) covers one answer.
        rig.io.arrive(initial(dcid), peer(1))
        rig.pass(2)
        assertEquals(2, rig.io.sent.size, "a late packet that covers the answer draws it")
    }

    @Test
    fun aCloseBeforeConfirmationIsAnsweredWithItsHandshakeDatagramAndItsOneRttCopy() {
        val stub = stub().apply { withholdConfirmation = true }
        val rig = closedAtZero(SplitClose(stub), seed = 10)
        assertEquals(listOf(LONG, SHORT), rig.io.sent.map { it.bytes[0] }, "the close went out as two datagrams")
        rig.io.arrive(initial(rig.dcid), peer(1))
        rig.pass(1)
        assertEquals(listOf(LONG, SHORT, LONG, SHORT), rig.io.sent.map { it.bytes[0] }, "the answer is both, Handshake first")
    }

    private class Rig(
        val rig: ServerRig,
        val dcid: ByteArray,
    ) {
        val io get() = rig.io
        val conns get() = rig.conns

        fun pass(at: Long) = rig.pass(at)
    }

    /** A server connection from [seed]'s client Initial, closed by the loop at 0: its close is the first datagram sent. */
    private fun closedAtZero(
        api: QuicheApi,
        seed: Int,
    ): Rig {
        val rig = ServerRig(api)
        val dcid = Random(seed).nextBytes(8)
        rig.io.arrive(initial(dcid), peer(1))
        rig.pass(0)
        rig.conns.single().submit(Closes(), CallerPlace.HandToLoop)
        rig.pass(0)
        return Rig(rig, dcid)
    }

    private fun stub() =
        StubQuicheApi().apply {
            emitOneDatagramOnClose = true
            connTimeout = DRAIN.nanoseconds
        }

    /** quiche's close before confirmation: its Handshake packet in one datagram, then its 1-RTT copy in another. */
    private class SplitClose(
        private val inner: StubQuicheApi,
    ) : QuicheApi by inner {
        private var copy = 0

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            if (copy == 1) {
                copy = 2
                UnsafeMemory.putByte(buf, SHORT)
                return SHORT_LENGTH
            }
            val written = inner.connSend(conn, buf, bufLen, sendInfo)
            if (written > 0 && copy == 0 && inner.connIsDraining(conn)) {
                copy = 1
                UnsafeMemory.putByte(buf, LONG)
            }
            return written
        }
    }

    /** Inside: closes the connection at once. */
    private class Closes : ConnWork() {
        override fun run(inside: Inside) = inside.close(QuicError.ApplicationError(1))

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val DRAIN = 180_000_000L

        /** A third of the drain: quiche's draining time is three probe timeouts. */
        const val PTO = DRAIN / 3
        const val SMALL = 100
        const val SHORT_LENGTH = 60
        const val LONG: Byte = 0xC0.toByte()
        const val SHORT: Byte = 0x40
    }
}
