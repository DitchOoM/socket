package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.CommonJvmQuicConfigCalls
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnEnd
import com.ditchoom.socket.quic.core.conn.ConnEvents
import com.ditchoom.socket.quic.core.conn.ConnPath
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.OneConnParts
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopWork
import com.ditchoom.socket.quic.core.loop.SelectorWait
import com.ditchoom.socket.quic.core.loop.startLoop
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamMap
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.toNativeSockAddr
import com.ditchoom.socket.quic.withQuicServer
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Failure containment on a core connection against today's server: what one stream operation throws fails that
 * operation, what processing one connection throws ends that connection, and only the loop's own failures and
 * `Error`s end every connection.
 */
class CoreFailureTierTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    @Test
    fun anOperationThatThrowsFailsItsCallerAndLeavesTheOtherStreamsWorking() =
        withRig { rig ->
            val connection = rig.connection
            val broken = connection.openStream()
            val healthy = connection.openStream()
            val planted = IllegalStateException("planted in a stream operation")

            val failure =
                assertFailsWith<IllegalStateException> {
                    suspendCancellableCoroutine<Outcome<Int>> { cont ->
                        rig.conn.submit(PlantedOp(broken.streamId, cont, rig.conn::submit, planted))
                    }
                }
            assertTrue(failure === planted || failure.cause === planted, "the caller got the planted exception: $failure")

            val payload = Random(1).nextBytes(1024)
            assertContentEquals(payload, echo(healthy, payload), "echo on the other stream, same connection")
            assertTrue(rig.ended.getNow(NOT_ENDED) === NOT_ENDED, "the connection did not end")
        }

    @Test
    fun aConnectionWorkThatThrowsEndsThatConnectionAndTheLoopKeepsRunning() =
        withRig { rig ->
            val stream = rig.connection.openStream()
            val planted = IllegalStateException("planted in connection work")
            val parkedRead = async { runCatching { stream.read(10.seconds) { } } }
            kotlinx.coroutines.delay(200)

            rig.conn.submit(ThrowingWork(planted))

            val end = assertIs<ConnEnd.Failed>(rig.ended.get(10, TimeUnit.SECONDS))
            assertSame(planted, end.cause)
            val read = assertIs<QuicCloseException>(parkedRead.await().exceptionOrNull())
            assertSame(planted, read.cause)

            val first = rig.runOnLoop()
            val second = rig.runOnLoop()
            assertTrue(second > first, "the loop kept passing: $first then $second")
            val later = LateWork()
            rig.conn.submit(later)
            assertEquals(1, later.gone.get(5, TimeUnit.SECONDS), "work for the failed connection is answered as gone")
        }

    @Test
    fun anIoThatFailsEndsEveryConnectionWithTheLoopFailure() =
        withRig { rig ->
            val planted = IllegalStateException("planted in receive")
            rig.io.plant = Plant.Throws(planted)
            rig.loop.submit(NothingWork)

            val end = assertIs<ConnEnd.LoopFailed>(rig.ended.get(10, TimeUnit.SECONDS))
            assertSame(planted, end.cause)
        }

    @Test
    fun anErrorFromConnectionProcessingIsNotContained() =
        withRig { rig ->
            val planted = PlantedError()
            rig.conn.submit(ThrowingErrorWork(planted))

            val end = assertIs<ConnEnd.LoopFailed>(rig.ended.get(10, TimeUnit.SECONDS))
            assertSame(planted, end.cause)
        }

    private suspend fun echo(
        stream: QuicByteStream,
        payload: ByteArray,
    ): ByteArray {
        val received = ByteArrayOutputStream()
        kotlinx.coroutines.coroutineScope {
            val reader =
                async {
                    while (received.size() < payload.size) {
                        if (stream.read(10.seconds) { received.write(it.readByteArray(it.remaining())) } !is ScopedRead.Data<*>) break
                    }
                }
            stream.writeFully(
                BufferFactory.Default.allocate(payload.size).apply {
                    writeBytes(payload)
                    resetForRead()
                },
                10.seconds,
            )
            reader.await()
        }
        return received.toByteArray()
    }

    private fun withRig(body: suspend CoroutineScope.(Rig) -> Unit) =
        runBlocking {
            skipOnMissingNativeLib(CoreFailureTierTests::class) {
                withQuicServer(port = 0, tlsConfig = tlsConfig(), quicOptions = options) {
                    val server =
                        launch(Dispatchers.IO) {
                            connections {
                                while (true) {
                                    val stream = acceptStream()
                                    launch {
                                        while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                                    }
                                }
                            }
                        }
                    withContext(Dispatchers.IO) {
                        val rig = Rig.start(port, options)
                        try {
                            kotlinx.coroutines.withTimeout(30.seconds) { body(rig) }
                        } finally {
                            rig.stop()
                        }
                    }
                    server.cancel()
                }
            }
        }

    private fun tlsConfig(): QuicTlsConfig = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private fun certPath(name: String): String =
        java.io
            .File(
                this::class.java.classLoader
                    .getResource("certs/$name")!!
                    .toURI(),
            ).absolutePath

    private companion object {
        val NOT_ENDED: ConnEnd = ConnEnd.Quietly
    }
}

private class PlantedError : Error()

private object NothingWork : LoopWork() {
    override fun run(loop: Loop) = Unit

    override fun gone() = Unit
}

/** Answers on the loop thread with the number of passes the loop has made. */
private class PassesWork : LoopWork() {
    val passes = CompletableFuture<Long>()

    override fun run(loop: Loop) {
        passes.complete(loop.counts.passes)
    }

    override fun gone() {
        passes.completeExceptionally(IllegalStateException("the loop had stopped"))
    }
}

private class ThrowingWork(
    private val planted: Exception,
) : ConnWork() {
    override fun run(inside: Conn.Inside) = throw planted

    override fun gone() = Unit
}

private class ThrowingErrorWork(
    private val planted: Error,
) : ConnWork() {
    override fun run(inside: Conn.Inside) = throw planted

    override fun gone() = Unit
}

private class LateWork : ConnWork() {
    val gone = CompletableFuture<Int>()

    override fun run(inside: Conn.Inside) {
        gone.completeExceptionally(IllegalStateException("ran on a failed connection"))
    }

    override fun gone() {
        gone.complete(1)
    }
}

private class PlantedOp(
    stream: com.ditchoom.socket.quic.QuicStreamId,
    cont: CancellableContinuation<Outcome<Int>>,
    submit: (ConnWork) -> Unit,
    private val planted: Exception,
) : StreamOp<Int>(StreamMap(), stream, Duration.INFINITE, cont, submit) {
    override fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt<Int> = throw planted

    override fun park(
        entry: StreamEntry,
        waiter: Waiter,
    ) {
        entry.reader = waiter
    }

    override fun parked(entry: StreamEntry): Waiter = entry.reader
}

private sealed interface Plant {
    object None : Plant

    class Throws(
        val failure: Exception,
    ) : Plant
}

/** The real socket's io, until a failure is planted: then its next receive throws. */
private class FlakyIo(
    private val real: DatagramBatchIo,
) : DatagramBatchIo by real {
    @Volatile
    var plant: Plant = Plant.None

    override fun receive(into: ReceiveBatch): ReceiveBatchResult =
        when (val now = plant) {
            Plant.None -> real.receive(into)
            is Plant.Throws -> throw now.failure
        }
}

/** A core client built by hand, the way a connect builds one but without ending its loop when the connection ends. */
private class Rig(
    val connection: CoreConnection,
    val conn: Conn,
    val loop: Loop,
    val io: FlakyIo,
    val ended: CompletableFuture<ConnEnd>,
    private val release: () -> Unit,
) {
    /** The loop's pass count as the loop thread sees it. */
    fun runOnLoop(): Long {
        val work = PassesWork()
        loop.submit(work)
        return work.passes.get(5, TimeUnit.SECONDS)
    }

    fun stop() {
        val closed = CompletableFuture<Unit>()
        loop.close { closed.complete(Unit) }
        closed.get(5, TimeUnit.SECONDS)
        release()
    }

    companion object {
        suspend fun start(
            port: Int,
            options: QuicOptions,
        ): Rig {
            val api = loadQuicheApi()
            val factory = BufferFactory.Default
            val channel = DatagramChannel.open().connect(InetSocketAddress("127.0.0.1", port))
            channel.configureBlocking(false)
            val local = (channel.localAddress as InetSocketAddress).toNativeSockAddr(factory)
            val peer = (channel.remoteAddress as InetSocketAddress).toNativeSockAddr(factory)
            val config = api.configNew(1)
            encodeAlpnList(options.alpnProtocols, factory).let { api.configSetApplicationProtos(config, addressOf(it), it.remaining()) }
            applyQuicOptions(options, CommonJvmQuicConfigCalls(api, config), QuicRole.Client)
            val serverName =
                factory.allocate(10).apply {
                    writeString("localhost", com.ditchoom.buffer.Charset.UTF8)
                    writeByte(0)
                    resetForRead()
                }
            val scid = generateScid(factory)
            val handle =
                api.connect(
                    addressOf(serverName),
                    9,
                    addressOf(scid),
                    QUIC_ISSUED_CONN_ID_LEN,
                    local.address,
                    local.length,
                    peer.address,
                    peer.length,
                    config,
                )
            val path =
                ConnPath(
                    api.recvInfoNew(peer.address, peer.length, local.address, local.length),
                    api.sendInfoNew(),
                    SendTarget.ConnectedPeer,
                )
            val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
            val sendStorage = factory.allocate(limits.maxDatagrams * limits.maxDatagramSize)
            val send = SendBatch(sendStorage, addressOf(sendStorage), limits)
            val receive =
                ReceiveBatch(
                    FixedPool(
                        limits.maxDatagrams,
                    ) { i -> factory.allocate(limits.maxDatagramSize).let { ReceiveBuffer(i, it, addressOf(it)) } },
                    limits,
                )
            val clock = LoopClock { System.nanoTime() }
            val parts = OneConnParts()
            val io = FlakyIo(NioOneByOne(channel, send))
            val loop = Loop(SelectorWait(channel, clock), clock, io, parts, receive, send)
            val scope = CoroutineScope(SupervisorJob())
            val connection =
                CoreConnection(
                    bufferFactory = factory,
                    remoteAddress = UdpSocket.resolve("127.0.0.1", port),
                    identity = QuicConnectionIdentity(QuicSessionId("tiers"), QuicWireConnectionId.Known("tiers")),
                    role = QuicRole.Client,
                    readPolicy = ReadPolicy.Bounded(10.seconds),
                    writePolicy = WritePolicy.Bounded(10.seconds),
                    scope = scope,
                )
            val ended = CompletableFuture<ConnEnd>()
            val events =
                object : ConnEvents by connection {
                    override fun closed(end: ConnEnd) {
                        connection.closed(end)
                        ended.complete(end)
                    }
                }
            val conn = Conn(api, handle, path, loop, Entrant.loopTurn(loop = 1, generation = 1), clock, events)
            connection.bind(conn)
            parts.attach(conn)
            startLoop(loop, "core-failure-tiers")
            conn.start()
            connection.established()
            return Rig(connection, conn, loop, io, ended) {
                conn.release()
                api.configFree(config)
                scope.cancel()
            }
        }
    }
}
