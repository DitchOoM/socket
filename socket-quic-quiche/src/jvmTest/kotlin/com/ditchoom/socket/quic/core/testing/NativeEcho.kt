package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.OwnBatch
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.startLoopThread
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.ready.NativePoller
import com.ditchoom.socket.quic.core.ready.PollerOpened
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NativeOpened
import com.ditchoom.socket.quic.core.udp.NativeSocket
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.toInetSocketAddress
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import java.net.InetSocketAddress
import java.util.function.IntSupplier

/**
 * [NioEcho] on the native stack: a loop on its own thread over a `qnet` socket and the native poller, serving one
 * connection whose quiche echoes every datagram back to the client that sent the first one.
 */
internal class NativeEcho(
    threadName: String,
    ceiling: UdpBatching = UdpBatching.Newest,
) {
    private val shim = loadNetShim()
    private val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
    private val receiveBuffers = List(limits.maxDatagrams) { ReceiveBuffer(it, nativeBytes(limits.maxDatagramSize)) }
    private val send = SendBatch(nativeBytes(limits.maxDatagrams * limits.maxDatagramSize), limits)
    private val socket =
        when (
            val opened =
                NativeSocket.open(
                    shim,
                    nativeBytes(NativeSocket.SPEC_BYTES),
                    InetSocketAddress("127.0.0.1", 0).toPathKey(),
                    SocketPeer.Unconnected,
                    1 shl 20,
                    1 shl 20,
                    ceiling,
                    limits.maxDatagrams,
                )
        ) {
            is NativeOpened.Opened -> opened.socket
            is NativeOpened.Refused -> throw AssertionError("qnet refused the echo socket: ${opened.refusal}")
            is NativeOpened.LayoutMismatch -> throw AssertionError(
                "qnet lays the spec block out as ${opened.mismatch.reported}, expected ${opened.mismatch.expected}",
            )
        }

    /** Where the echo listens. */
    val address: InetSocketAddress = socket.local.toInetSocketAddress() ?: throw AssertionError("no local address")
    val sink = RecordingSink()
    private val io =
        NativeIo(
            shim,
            socket,
            listOf(nativeBytes(NativeIo.SEND_RECORD * limits.maxDatagrams)),
            nativeBytes(
                NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE,
            ),
        )

    /** The echo socket's descriptor, as the poller watches it. */
    val descriptor: Int get() = io.descriptor()
    val loop =
        Loop(
            id = 3,
            name = threadName,
            poller =
                when (val opened = NativePoller.open(shim, 64)) {
                    is PollerOpened.Opened -> opened.poller
                    is PollerOpened.Refused -> throw AssertionError("qnet refused the poller: ${opened.refusal}")
                },
            clock = MonotonicClock,
            receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { receiveBuffers[it] }, limits),
            freeOnStop = Runnable { },
        )
    private val sendPort = SendPort(io, loop.port, CallerLanes.None, RecordingWork(), loop.counts, loop.callerCounts)
    lateinit var conn: Conn
        private set
    lateinit var thread: Thread
        private set

    /** Starts serving one connection that echoes to [client]. */
    fun start(client: InetSocketAddress): NativeEcho {
        conn =
            testConn(
                // quiche stamps its datagrams on the clock the loop reads, as libquiche does.
                EchoQuiche(StubQuicheApi { MonotonicClock.now().value }),
                CoreTestRules,
                SendTarget.ServerReply(client.toPathKey(), ReplySource.Undecodable),
                TraceCapture.Off,
                loop,
                sendPort,
                MonotonicClock,
                sink,
                ConnCounts(),
            )
        loop.place(Endpoint(SocketRef(IntSupplier { io.descriptor() }), io, sendPort, Route.Fixed(conn, OwnBatch(send)), Runnable { }))
        conn.start()
        thread = startLoopThread(loop, threadName)
        return this
    }

    private val threadName = threadName
}
