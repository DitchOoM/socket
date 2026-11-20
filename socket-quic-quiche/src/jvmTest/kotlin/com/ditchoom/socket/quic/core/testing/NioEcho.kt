package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.OwnBatch
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.startLoopThread
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.ready.SelectorPoller
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.jdkStackOfThisRuntime
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.JdkBacked
import com.ditchoom.socket.quic.core.udp.JdkBytes
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendView
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

internal fun jdk(bytes: NativeBytes): JdkBytes =
    when (val backed = JdkBytes.of(bytes)) {
        is JdkBacked.Backed -> backed.bytes
        is JdkBacked.NotJdkBacked -> throw AssertionError("the network factory is JDK-backed")
    }

/**
 * A loop on its own thread over a real non-blocking socket, serving one connection whose quiche echoes every datagram
 * back to [client]: the whole receive, turn and send path, with a real selector and socket.
 */
internal class NioEcho(
    val server: DatagramChannel,
    client: InetSocketAddress,
    threadName: String,
) {
    private val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
    private val receiveBuffers = List(limits.maxDatagrams) { ReceiveBuffer(it, nativeBytes(limits.maxDatagramSize)) }
    private val send = SendBatch(nativeBytes(limits.maxDatagrams * limits.maxDatagramSize), limits)
    val sink = RecordingSink()
    val loop =
        Loop(
            id = 2,
            name = threadName,
            poller = SelectorPoller(jdkStackOfThisRuntime().scan),
            clock = MonotonicClock,
            receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { receiveBuffers[it] }, limits),
            freeOnStop = Runnable { },
        )
    private val io = NioOneByOne(server, listOf(SendView(send.storage, jdk(send.storage))), receiveBuffers.map { jdk(it.bytes) })
    private val socket = SendPort(io, loop.port, CallerLanes.None, RecordingWork(), loop.counts, loop.callerCounts)
    val conn: Conn =
        testConn(
            // quiche stamps its datagrams on the clock the loop reads, as libquiche does.
            EchoQuiche(StubQuicheApi(MonotonicClock)),
            CoreTestRules,
            SendTarget.ServerReply(client.toPathKey(), ReplySource.Undecodable),
            TraceCapture.Off,
            loop,
            socket,
            MonotonicClock,
            sink,
            ConnCounts(),
        )
    val thread: Thread

    init {
        loop.place(Endpoint(SocketRef(server), io, socket, Route.Fixed(conn, OwnBatch(send)), Runnable { }))
        conn.start()
        thread = startLoopThread(loop, threadName)
    }
}
