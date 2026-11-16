package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals

/** Datagrams out: everything quiche has, up to a full batch, each committed to the path's target and recorded. */
class EgressTests {
    private val api = StubQuicheApi()
    private val q = QuicheConnRef(api, QuicheConn(1L))
    private val path = SinglePath(q, SockAddr(0L, 0), SockAddr(0L, 0), SendTarget.ConnectedPeer)
    private val flight = ConnFlight(FlightSize(16), FlightArena(nativeBytes(8192)), PathKey(4, 1, 0L, 1L), TraceCapture.Off, 0L)

    @Test
    fun aWriteTakesWhatQuicheHasAndRecordsEachDatagram() {
        val out = SendBatch(nativeBytes(4 * 1500), BatchLimits(4, 1500))
        api.connSendQueue.addAll(listOf(100, 200))

        assertEquals(Wrote.All, Egress(q).write(out, path, Nanos(0), flight))

        assertEquals(2, out.pending)
        assertEquals(listOf(100, 200), List(2) { out.lengthOf(it) })
        val recorded = ArrayList<TraceEvent>()
        flight.dump(TraceSink { recorded += it })
        assertEquals(listOf(100, 200), recorded.map { (it as TraceEvent.DgramOut).len })
    }

    @Test
    fun aFullBatchStopsTheWriteWithMoreWaiting() {
        val out = SendBatch(nativeBytes(2 * 1500), BatchLimits(2, 1500))
        api.connSendQueue.addAll(listOf(100, 100, 100))

        assertEquals(Wrote.BatchFull, Egress(q).write(out, path, Nanos(0), flight))
        assertEquals(2, out.pending)
    }
}
