package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.Moved
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.replay.conditions.LOCALHOST
import com.ditchoom.socket.quic.replay.conditions.ReplayClient
import com.ditchoom.socket.quic.replay.conditions.replayClient
import com.ditchoom.socket.quic.replay.conditions.replayServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.net.InetSocketAddress
import java.nio.Buffer
import java.util.PriorityQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Which end of a [VirtualPair]. */
internal enum class End { Client, Server }

/** Which datagrams toward the client the link delivers. */
internal enum class Delivery {
    /** Every one, wherever the server addressed it. */
    ToEnd,

    /** Only those addressed to the port the client's socket is bound to when they arrive, as real sockets do. */
    ToAddress,
}

/** When a [VirtualPair]'s body runs: once both ends established, or as soon as the client started its handshake. */
internal enum class PairStart { Established, Started }

/** One datagram an end handed its socket, at virtual [at] nanoseconds from the pair's start. */
internal class Sent(
    val from: End,
    val at: Long,
    val bytes: ByteArray,
)

/**
 * A real core client and a real core server on one thread and one virtual clock, quiche's own clock included, joined by
 * a link that delays every datagram by [oneWay] each way and loses none. Tests drive time with [runUntil] and
 * [runFor]; coroutines they launch on the pair's scope run between passes.
 */
internal class VirtualPair private constructor(
    val oneWay: Duration,
    private val api: QuicheApi,
    private val delivery: Delivery,
) {
    val clock = VirtualClock(Nanos(ORIGIN))
    private val inFlight = PriorityQueue<InFlight>(compareBy<InFlight>({ it.arrives }, { it.order }))
    private var order = 0L
    private val waiting = mapOf(End.Client to ArrayDeque<ByteArray>(), End.Server to ArrayDeque())

    /** Every datagram either end sent, in order, the lost ones included. */
    val sent = ArrayList<Sent>()
    private val dropping = mutableMapOf(End.Client to 0, End.Server to 0)

    /** The link loses the next [count] datagrams [end] sends. */
    fun lose(
        end: End,
        count: Int,
    ) {
        dropping[end] = count
    }

    private val held = mapOf(End.Client to ArrayList<InFlight>(), End.Server to ArrayList())
    private val holding = HashSet<End>()

    /** The link holds what [end] sends from now on until [release]. */
    fun hold(end: End) {
        holding += end
    }

    /** What [end] sent while held leaves now, in order, and arrives together: one receive batch at the other end. */
    fun release(end: End) {
        holding -= end
        val arrives = clock.at.value + oneWay.inWholeNanoseconds
        for (datagram in held.getValue(end)) inFlight.add(InFlight(arrives, order++, datagram.to, datagram.bytes, datagram.port))
        held.getValue(end).clear()
    }

    /** How many datagrams the link holds from [end]. */
    fun heldFrom(end: End): Int = held.getValue(end).size

    /** Datagrams the client's quiche was handed, counted at the client's api. */
    var clientRecvs = 0
        private set

    /** The ports the client's next moves bind to, in order, as an OS may hand back a port the socket left. */
    val movePorts = ArrayDeque<Int>()

    /** The client's quiche handle, seen with its first datagram. */
    private var clientConn = QuicheConn(1L)

    /** Destination connection ids the client's quiche holds for paths it has not used. */
    fun clientSpareIds(): Long = api.connAvailableDcids(clientConn)

    /** The port the client's socket is bound to now. */
    fun clientPort(): Int = clientKey.port

    lateinit var server: CoreServer
        private set
    lateinit var client: ReplayClient
        private set
    private lateinit var serverKey: PathKey
    private lateinit var clientKey: PathKey

    /** Virtual nanoseconds since the pair started. */
    fun now(): Long = clock.at.value - ORIGIN

    /** The datagrams [end] sent at or after [from] nanoseconds. */
    fun sentBy(
        end: End,
        from: Long = 0L,
    ): List<Sent> = sent.filter { it.from == end && it.at >= from }

    /** Passes both loops, delivering what is due, until [done] holds; fails past [within] of virtual time. */
    suspend fun runUntil(
        within: Duration = 10.seconds,
        done: () -> Boolean,
    ) {
        val limit = now() + within.inWholeNanoseconds
        while (true) {
            val due = settle()
            if (done()) return
            check(now() < limit) { "not done within $within of virtual time" }
            advanceTo(minOf(due, nextArrival(), clock.at.value + limit - now()).coerceAtLeast(clock.at.value + MIN_STEP))
        }
    }

    /** Runs [length] of virtual time. */
    suspend fun runFor(length: Duration) {
        val until = clock.at.value + length.inWholeNanoseconds
        while (true) {
            val due = settle()
            if (clock.at.value >= until) return
            advanceTo(minOf(due, nextArrival(), until).coerceAtLeast(clock.at.value + MIN_STEP).coerceAtMost(until))
        }
    }

    private fun advanceTo(nanos: Long) {
        clock.at = Nanos(nanos)
        api.setThreadVirtualTimeNanos(nanos)
    }

    private fun nextArrival(): Long = if (inFlight.isEmpty()) Long.MAX_VALUE else inFlight.peek().arrives

    private suspend fun settle(): Long {
        var due = Long.MAX_VALUE
        repeat(ROUNDS) {
            while (inFlight.isNotEmpty() && inFlight.peek().arrives <= clock.at.value) {
                val datagram = inFlight.poll()
                if (delivered(datagram)) waiting.getValue(datagram.to).addLast(datagram.bytes)
            }
            val now = clock.at
            due = minOf(passOf(server.loop, now), passOf(client.loop, now))
            repeat(YIELDS) { yield() }
        }
        return due
    }

    private fun passOf(
        loop: Loop,
        now: Nanos,
    ): Long = loop.pass(now).fold({ Long.MAX_VALUE }, { it })

    private fun delivered(datagram: InFlight): Boolean =
        when (delivery) {
            Delivery.ToEnd -> true
            Delivery.ToAddress -> datagram.to == End.Server || datagram.port == clientKey.port
        }

    private class InFlight(
        val arrives: Long,
        val order: Long,
        val to: End,
        val bytes: ByteArray,
        /** The port it is addressed to. */
        val port: Int,
    )

    /** The link loses every datagram [end] sent that has not arrived yet; returns how many. */
    fun loseInFlight(end: End): Int {
        val toward = if (end == End.Client) End.Server else End.Client
        val lost = inFlight.filter { it.to == toward }
        inFlight.removeAll(lost.toSet())
        return lost.size
    }

    /** One end's socket on the link. */
    private inner class Socket(
        private val end: End,
    ) : DatagramBatchIo {
        override val capability: BatchCapability = BatchCapability.OneByOne

        override fun send(batch: SendBatch): SendBatchResult {
            val view = (batch.storage.owner.unwrapFully() as BaseJvmBuffer).byteBuffer.duplicate()
            for (n in 0 until batch.pending) {
                (view as Buffer).limit(batch.offsetOf(n) + batch.lengthOf(n)).position(batch.offsetOf(n))
                val bytes = ByteArray(batch.lengthOf(n))
                view.get(bytes)
                sent += Sent(end, now(), bytes)
                if (dropping.getValue(end) > 0) {
                    dropping[end] = dropping.getValue(end) - 1
                    continue
                }
                val to = if (end == End.Client) End.Server else End.Client
                val port =
                    when (val target = batch.targetOf(n)) {
                        is SendTarget.ServerReply -> target.to.port
                        is SendTarget.ConnectedPeer -> if (end == End.Client) serverKey.port else clientKey.port
                    }
                val datagram = InFlight(clock.at.value + oneWay.inWholeNanoseconds, order++, to, bytes, port)
                if (end in holding) held.getValue(end).add(datagram) else inFlight.add(datagram)
            }
            return SendBatchResult.AllTaken
        }

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            val queue = waiting.getValue(end)
            var got = 0
            while (queue.isNotEmpty()) {
                val buffer = into.take()
                if (buffer !is ReceiveBuffer) break
                val bytes = queue.removeFirst()
                val view = (buffer.bytes.owner.unwrapFully() as BaseJvmBuffer).byteBuffer
                (view as Buffer).clear()
                view.put(bytes)
                val from = if (end == End.Client) serverKey else clientKey
                into.received(buffer, bytes.size, from, ArrivedOn.Unreported)
                got++
            }
            return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
        }

        override fun probe(): SocketProbe = SocketProbe.Open

        override fun reopen(): Reopened = Reopened.SameEndpoint

        /**
         * The client's socket moves to the next port, or to the port a test queued ([movePorts]): the server sees its
         * datagrams come from there.
         */
        override fun moveTo(bind: PathKey): Moved {
            clientKey = clientKey.copy(port = movePorts.removeFirstOrNull() ?: (clientKey.port + 1))
            return Moved.To(clientKey)
        }

        override fun close(): SocketClosed = SocketClosed
    }

    /** The client's api: counts every datagram handed to its quiche. */
    private inner class CountingClientApi(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            info: QuicheRecvInfo,
        ): Int {
            clientRecvs++
            clientConn = conn
            return inner.connRecv(conn, buf, bufLen, info)
        }
    }

    companion object {
        private const val ORIGIN = 1_000_000_000L
        private const val MIN_STEP = 1_000L
        private const val ROUNDS = 4
        private const val YIELDS = 4

        /** Builds the pair with [options] on both ends, runs [body] once the client's handshake completed, and stops both. */
        fun with(
            options: QuicOptions,
            oneWay: Duration = 30.milliseconds,
            tls: QuicTlsConfig = coreTlsConfig(),
            serverConfig: ServerConfig = ServerConfig(),
            delivery: Delivery = Delivery.ToEnd,
            start: PairStart = PairStart.Established,
            clientApi: (QuicheApi) -> QuicheApi = { it },
            serverApi: (QuicheApi) -> QuicheApi = { it },
            body: suspend CoroutineScope.(VirtualPair) -> Unit,
        ) = runBlocking {
            val api = loadQuicheApi()
            val pair = VirtualPair(oneWay, api, delivery)
            api.setThreadVirtualTimeNanos(ORIGIN)
            try {
                pair.server =
                    replayServer(serverApi(api), options, BufferFactory.Default, pair.Socket(End.Server), pair.clock, tls, serverConfig)
                pair.serverKey = InetSocketAddress(LOCALHOST, pair.server.port).toPathKey()
                pair.client =
                    replayClient(
                        clientApi(pair.CountingClientApi(api)),
                        pair.isolated(options),
                        BufferFactory.Default,
                        pair.Socket(End.Client),
                        pair.clock,
                        pair.server.port,
                        1L,
                        LinkClass.Ethernet,
                    )
                pair.clientKey = pair.client.local
                pair.client.parts.start()
                if (start == PairStart.Started) return@runBlocking body(pair)
                pair.runUntil {
                    pair.client.connection.end() is EdgeEnd.Open &&
                        pair.client.connection.state.value
                            .isEstablished()
                }
                // The server establishes once the client's last handshake flight arrives, and both acknowledge it.
                pair.runFor(oneWay * 4)
                body(pair)
            } finally {
                pair.stop()
                api.clearThreadVirtualTime()
            }
        }
    }

    /**
     * The client closes and a new one, with [options], takes its place on the link from a port of its own. Its first
     * flight goes out in the next pass; [ReplayClient.offer] says whether it waits for 0-RTT.
     */
    suspend fun reconnect(
        options: QuicOptions,
        wrapApi: (QuicheApi) -> QuicheApi = { it },
    ): ReplayClient {
        val old = client
        old.connection.closeNow(com.ditchoom.socket.quic.QuicError.NoError)
        runUntil { old.connection.end() is EdgeEnd.Ended }
        old.loop.close(Runnable { })
        old.loop.run()
        old.scope.cancel()
        loseInFlight(End.Server)
        loseInFlight(End.Client)
        client =
            replayClient(
                wrapApi(CountingClientApi(api)),
                isolated(options),
                BufferFactory.Default,
                Socket(End.Client),
                clock,
                server.port,
                ++seeds,
                LinkClass.Ethernet,
            )
        clientKey = client.local
        client.parts.start()
        return client
    }

    private var seeds = 1L

    /** The server's loop runs its stop now, as a restart's first half does; the pair stops it again at its end. */
    fun stopServer() {
        server.loop.close(Runnable { })
        server.loop.run()
    }

    /** The server stops and a new one, with [options], takes its place on the link; the client must reconnect. */
    suspend fun restartServer(options: QuicOptions) {
        stopServer()
        loseInFlight(End.Server)
        loseInFlight(End.Client)
        server = replayServer(api, options, BufferFactory.Default, Socket(End.Server), clock, coreTlsConfig())
        serverKey = InetSocketAddress(LOCALHOST, server.port).toPathKey()
    }

    /** Tickets this pair's clients keep and offer: the pair's own, so none from another test's server on a reused port. */
    private val sessions = QuicSessionMemory()

    /** [options] with the process's default session store made this pair's own; a test's own store stays. */
    fun isolated(options: QuicOptions): QuicOptions =
        if (options.sessionStore === QuicSessionStore.Default) options.copy(sessionStore = QuicSessionStore.InMemory(sessions)) else options

    /** The server's accepted connection: it is offered from a thread of the server's own, so awaited on the wall clock. */
    suspend fun accept(): CoreConnection = withTimeout(10.seconds) { server.accept() }

    private fun stop() {
        client.connection.closeNow(com.ditchoom.socket.quic.QuicError.NoError)
        client.loop.close(Runnable { })
        client.loop.run()
        server.loop.close(Runnable { })
        server.loop.run()
        client.scope.cancel()
    }
}

private fun com.ditchoom.socket.quic.QuicConnectionState.isEstablished(): Boolean =
    this is com.ditchoom.socket.quic.QuicConnectionState.Established
