package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.ApiQuicConfigCalls
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.edge.LoopDriver
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.toNativeSockAddr
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Rule
import java.net.InetSocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A start-up herd, run on virtual time: a thousand clients start their handshake in the same instant against a server
 * whose network is in memory, and the server's loop is driven one pass at a time. Every handshake must complete before
 * any client's idle timeout, which is nothing a scheduler, a socket or a machine's speed can change.
 */
class CoreServerStartupHerdTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    @Test
    fun aThousandClientsThatStartTogetherAllCompleteTheirHandshakesBeforeTheirIdleTimeout() =
        herd(kernelBuffer = Int.MAX_VALUE, wave = Int.MAX_VALUE)

    /**
     * The same herd against a kernel receive buffer of 200 datagrams, which is what an emulator grants however much the
     * server asks for: the clients send in waves of 200 Initials with one server pass between waves, and a datagram that
     * arrives at a full buffer is dropped as a kernel would. A server that leaves what it did not admit in the socket
     * lets the buffer overflow and loses Initials, whose clients then retransmit on a doubling timeout.
     */
    @Test
    fun aThousandClientsStartingTogetherAllCompleteTheirHandshakesThroughAKernelBufferOfTwoHundredDatagrams() =
        herd(kernelBuffer = 200, wave = 200)

    private fun herd(
        kernelBuffer: Int,
        wave: Int,
    ) = runBlocking {
        val api = loadQuicheApi()
        val factory = BufferFactory.Default
        val network = MemoryNetwork(kernelBuffer)
        val server =
            coreServe(
                bind = InetSocketAddress("127.0.0.1", 0).toPathKey(),
                tls = coreTlsConfig(),
                options = coreTestOptions,
                factory = factory,
                api = api,
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
                wrapIo = { network },
                clock = LoopClock { Nanos(network.now) },
                driver = LoopDriver.Caller,
            )
        val serverAddress = InetSocketAddress("127.0.0.1", server.port)
        val peer = serverAddress.toNativeSockAddr(factory)
        val config = api.configNew(1)
        encodeAlpnList(
            coreTestOptions.alpnProtocols,
            factory,
        ).let { api.configSetApplicationProtos(config, addressOf(it), it.remaining()) }
        applyQuicOptions(coreTestOptions, ApiQuicConfigCalls(api, config), QuicRole.Client)
        val serverName =
            factory.allocate(10).apply {
                writeString("localhost", Charset.UTF8)
                writeByte(0)
                resetForRead()
            }
        val out = factory.allocate(MAX_DATAGRAM)
        val outView = (out.unwrapFully() as BaseJvmBuffer).byteBuffer
        val sendInfo = api.sendInfoNew()
        val clients =
            List(CLIENTS) { i ->
                val local = InetSocketAddress("127.0.0.1", FIRST_PORT + i)
                val localNative = local.toNativeSockAddr(factory)
                network.now = START
                api.setThreadVirtualTimeNanos(START)
                val scid = generateScid(factory, Random(i))
                val conn =
                    api.connect(
                        addressOf(serverName),
                        9,
                        addressOf(scid),
                        QUIC_ISSUED_CONN_ID_LEN,
                        localNative.address,
                        localNative.length,
                        peer.address,
                        peer.length,
                        config,
                    )
                Client(i, local.toPathKey(), conn, api.recvInfoNew(peer.address, peer.length, localNative.address, localNative.length))
            }
        val byPath = clients.associateBy { it.path }
        try {
            var now = START
            val end = START + OBSERVED.inWholeNanoseconds
            while (now <= end && clients.any { it.state == State.Handshaking }) {
                network.now = now
                api.setThreadVirtualTimeNanos(now)
                var next = Long.MAX_VALUE
                var rounds = 0
                var sent = 0
                do {
                    for (client in clients) {
                        if (client.state != State.Handshaking) continue
                        val due = api.connTimeoutNanos(client.conn)
                        if (due == 0L) api.connOnTimeout(client.conn)
                        while (true) {
                            val length = api.connSend(client.conn, addressOf(out), MAX_DATAGRAM, sendInfo)
                            if (length <= 0) break
                            if (isInitial(outView.get(0))) client.initials.add(now)
                            network.arrive(Datagram(client.path, ByteArray(length).also { outView.duplicate().get(it, 0, length) }))
                            if (++sent % wave == 0) server.loop.pass(Nanos(now))
                        }
                    }
                    val pending = network.toServer.isNotEmpty()
                    do {
                        val due = server.loop.pass(Nanos(now))
                        due.fold(none = {}, due = { next = minOf(next, it) })
                    } while (network.toServer.isNotEmpty())
                    val replies = network.toClients.toList()
                    network.toClients.clear()
                    for (reply in replies) {
                        val client = byPath.getValue(reply.path)
                        val bytes = factory.allocate(reply.bytes.size).apply { writeBytes(reply.bytes) }
                        api.connRecv(client.conn, addressOf(bytes), reply.bytes.size, client.recvInfo)
                        bytes.freeNativeMemory()
                    }
                    for (client in clients) {
                        if (client.state != State.Handshaking) continue
                        if (api.connIsEstablished(client.conn)) {
                            client.state = State.Established
                            client.establishedAt = now
                        } else if (api.connIsClosed(client.conn)) {
                            client.state = State.Closed
                            client.closedAt = now
                        }
                    }
                    rounds++
                } while ((pending || replies.isNotEmpty()) && rounds < 200)
                for (client in clients) {
                    if (client.state != State.Handshaking) continue
                    val timeout = api.connTimeoutNanos(client.conn)
                    if (timeout >= 0) next = minOf(next, now + timeout)
                }
                now = if (next <= now) now + MILLI else next
            }
            val established = clients.count { it.state == State.Established }
            val closed = clients.filter { it.state == State.Closed }
            val initialsPerClient = clients.groupingBy { it.initials.size }.eachCount().toSortedMap()
            val slowest = clients.filter { it.state == State.Established }.maxOfOrNull { (it.establishedAt - START) / MILLI } ?: -1
            val firstClosed = closed.minOfOrNull { (it.closedAt - START) / MILLI } ?: -1
            println(
                "[startup herd] $CLIENTS clients: established=$established closed=${closed.size} (first at $firstClosed ms), " +
                    "slowest handshake=$slowest ms, server dropped ${server.counts.droppedAdmission} Initials for admission, " +
                    "accepted=${server.counts.accepted}, " +
                    "kernel dropped ${network.kernelDrops}, backlog dropped ${server.counts.droppedBacklog}, " +
                    "Initials sent per client (initials=clients)=$initialsPerClient, " +
                    "retransmit times of the last client=${clients.last().initials.map { (it - START) / MILLI }} ms",
            )
            assertEquals(
                0,
                closed.size,
                "clients that ended before their handshake completed (idle timeout ${coreTestOptions.idleTimeout}): ${closed.size}",
            )
            assertEquals(CLIENTS, established, "handshakes completed")
            assertEquals(0, network.kernelDrops, "Initials the kernel buffer dropped because the server had not taken them yet")
        } finally {
            api.clearThreadVirtualTime()
            clients.forEach { api.connFree(it.conn) }
            // Nothing else runs the loop: asking for the close and then running it to its end is the whole shutdown.
            val closing = async { server.close() }
            yield()
            server.loop.run()
            closing.await()
        }
    }

    private enum class State { Handshaking, Established, Closed }

    private class Client(
        val index: Int,
        val path: PathKey,
        val conn: QuicheConn,
        val recvInfo: com.ditchoom.socket.quic.QuicheRecvInfo,
    ) {
        var state = State.Handshaking
        var establishedAt = 0L
        var closedAt = 0L
        val initials = ArrayList<Long>()
    }

    private class Datagram(
        val path: PathKey,
        val bytes: ByteArray,
    )

    /**
     * The network between the clients and the server: datagrams cross at once and in order. The server's socket holds
     * [kernelBuffer] datagrams; one that arrives while it is full is lost, as a kernel loses it.
     */
    private class MemoryNetwork(
        private val kernelBuffer: Int,
    ) : DatagramBatchIo {
        var kernelDrops = 0

        fun arrive(datagram: Datagram) {
            if (toServer.size >= kernelBuffer) kernelDrops++ else toServer.addLast(datagram)
        }

        override val capability: BatchCapability = BatchCapability.OneByOne
        var now = 0L
        val toServer = ArrayDeque<Datagram>()
        val toClients = ArrayList<Datagram>()

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            var got = 0
            while (toServer.isNotEmpty()) {
                val buffer = into.take()
                if (buffer !is ReceiveBuffer) break
                val datagram = toServer.removeFirst()
                val view = (buffer.bytes.owner.unwrapFully() as BaseJvmBuffer).byteBuffer
                (view as Buffer).clear()
                view.put(datagram.bytes)
                into.received(buffer, datagram.bytes.size, datagram.path, ArrivedOn.Unreported)
                got++
            }
            return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
        }

        override fun send(batch: SendBatch): SendBatchResult {
            val view: ByteBuffer = (batch.storage.owner.unwrapFully() as BaseJvmBuffer).byteBuffer.duplicate()
            for (n in 0 until batch.pending) {
                val target = batch.targetOf(n) as SendTarget.ServerReply
                (view as Buffer).limit(batch.offsetOf(n) + batch.lengthOf(n)).position(batch.offsetOf(n))
                val bytes = ByteArray(batch.lengthOf(n))
                view.get(bytes)
                toClients.add(Datagram(target.to, bytes))
            }
            return SendBatchResult.AllTaken
        }

        override fun probe(): SocketProbe = SocketProbe.Open

        override fun reopen(): Reopened = Reopened.SameEndpoint

        override fun moveTo(bind: com.ditchoom.socket.quic.PathKey): com.ditchoom.socket.quic.core.udp.Moved =
            com.ditchoom.socket.quic.core.udp.Moved.Unsupported

        override fun close(): SocketClosed = SocketClosed
    }

    private fun isInitial(first: Byte): Boolean = first.toInt() and 0xF0 == 0xC0

    private companion object {
        const val CLIENTS = 1_024
        const val FIRST_PORT = 20_000
        const val MAX_DATAGRAM = 1_500
        const val START = 1_000_000_000L
        const val MILLI = 1_000_000L
        val OBSERVED = 30.seconds
    }
}
