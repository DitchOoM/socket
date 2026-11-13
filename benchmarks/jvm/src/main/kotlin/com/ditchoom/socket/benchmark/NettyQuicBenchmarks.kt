package com.ditchoom.socket.benchmark

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.quic.InsecureQuicTokenHandler
import io.netty.handler.codec.quic.QuicChannel
import io.netty.handler.codec.quic.QuicClientCodecBuilder
import io.netty.handler.codec.quic.QuicServerCodecBuilder
import io.netty.handler.codec.quic.QuicSslContextBuilder
import io.netty.handler.codec.quic.QuicStreamChannel
import io.netty.handler.codec.quic.QuicStreamType
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/*
 * Netty QUIC (io.netty:netty-codec-native-quic, which wraps Cloudflare quiche, like this library) on the same
 * loopback shapes as QuicStreamBenchmarks.kt: the same payload sizes, the same flow-control limits, the same
 * ECHO_MARKER protocol, the same JMH modes. What differs is the wrapper around quiche: Netty's NIO event loop
 * and ByteBuf pipeline instead of this library's driver loop and coroutines.
 *
 * Threads: one NIO event loop for the server and one for the client, the shape Netty applications run. The
 * benchmark thread blocks (park) until the event loop has delivered the reply, as the QUIC benchmarks' own
 * runBlocking does.
 */

private const val NETTY_ALPN = "bench"

/** Counts what a stream receives and parks the benchmark thread until enough has arrived (or the peer's FIN). */
internal class NettyStreamSink : ChannelInboundHandlerAdapter() {
    private val received = AtomicLong()

    @Volatile private var finished = false

    @Volatile private var waiter: Thread? = null

    fun reset() {
        received.set(0)
        finished = false
    }

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        val buf = msg as ByteBuf
        received.addAndGet(buf.readableBytes().toLong())
        buf.release()
        LockSupport.unpark(waiter)
    }

    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any,
    ) {
        if (evt === ChannelInputShutdownReadComplete.INSTANCE) {
            finished = true
            LockSupport.unpark(waiter)
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        LockSupport.unpark(waiter)
        ctx.fireChannelWritabilityChanged()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        finished = true
        LockSupport.unpark(waiter)
    }

    /** Waits for [bytes] to have arrived; returns the count (fewer only if the peer ended first). */
    fun awaitBytes(bytes: Int): Int {
        waiter = Thread.currentThread()
        val deadline = System.nanoTime() + IO_TIMEOUT.inWholeNanoseconds
        while (received.get() < bytes && !finished) {
            check(System.nanoTime() < deadline) { "timed out waiting for $bytes bytes, have ${received.get()}" }
            LockSupport.parkNanos(this, 1_000_000_000L)
        }
        return received.get().toInt()
    }

    /** Waits for the peer's FIN; returns how many bytes arrived before it. */
    fun awaitFinished(): Int {
        waiter = Thread.currentThread()
        val deadline = System.nanoTime() + IO_TIMEOUT.inWholeNanoseconds
        while (!finished) {
            check(System.nanoTime() < deadline) { "timed out waiting for the peer's FIN" }
            LockSupport.parkNanos(this, 1_000_000_000L)
        }
        return received.get().toInt()
    }

    /** Parks until [channel] accepts more writes (its outbound buffer drained below the low water mark). */
    fun awaitWritable(channel: Channel) {
        waiter = Thread.currentThread()
        val deadline = System.nanoTime() + IO_TIMEOUT.inWholeNanoseconds
        while (!channel.isWritable) {
            check(System.nanoTime() < deadline) { "timed out waiting for the stream to become writable" }
            LockSupport.parkNanos(this, 1_000_000_000L)
        }
    }
}

/** The server's stream handler: the same protocol as serveStream in QuicLoopback.kt. */
private class NettyEchoOrSink : ChannelInboundHandlerAdapter() {
    private var echo: Boolean? = null

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        val buf = msg as ByteBuf
        val first = echo ?: (buf.isReadable && buf.getByte(buf.readerIndex()) == ECHO_MARKER).also { echo = it }
        if (first) ctx.write(buf) else buf.release()
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
        ctx.fireChannelReadComplete()
    }

    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any,
    ) {
        if (evt === ChannelInputShutdownReadComplete.INSTANCE) {
            // Deferred to the next event-loop task: shutting the output down inside the input-shutdown event dispatch
            // intermittently never delivered the FIN to the peer (1 in 50 to 1 in 1000 streams on Netty 4.2.20).
            ctx.executor().execute { (ctx.channel() as QuicStreamChannel).shutdownOutput() }
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        ctx.close()
    }
}

/** One Netty QUIC server on 127.0.0.1 and one client connection to it, held open for the life of a trial. */
internal class NettyQuicLoopback : AutoCloseable {
    private val serverGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val clientGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val handlerExecutor: io.netty.util.concurrent.EventExecutorGroup? =
        if (System.getProperty("bench.nettyHandlerExecutor") == "true") io.netty.util.concurrent.DefaultEventExecutorGroup(4) else null
    private val serverChannel: Channel
    private val clientDatagram: Channel
    val connection: QuicChannel

    init {
        val dir = System.getProperty("bench.certDir") ?: error("bench.certDir is not set: run through Gradle")
        // Both ends: the flow-control limits of QuicLoopback.OPTIONS.
        val serverCodec =
            QuicServerCodecBuilder()
                .sslContext(
                    QuicSslContextBuilder
                        .forServer(
                            File(dir, "cert.key"),
                            null,
                            File(dir, "cert.crt"),
                        ).applicationProtocols(NETTY_ALPN)
                        .build(),
                ).maxIdleTimeout(10, TimeUnit.MINUTES)
                .initialMaxData(64L * 1024 * 1024)
                .initialMaxStreamDataBidirectionalLocal(16L * 1024 * 1024)
                .initialMaxStreamDataBidirectionalRemote(16L * 1024 * 1024)
                .initialMaxStreamsBidirectional(1_000_000)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .streamOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .handler(
                    object : ChannelInitializer<QuicChannel>() {
                        override fun initChannel(ch: QuicChannel) {}
                    },
                ).streamHandler(
                    object : ChannelInitializer<QuicStreamChannel>() {
                        override fun initChannel(ch: QuicStreamChannel) {
                            // -Dbench.nettyHandlerExecutor=true: the handler runs on an EventExecutorGroup, off the
                            // event loop, which is where Netty applications put a handler that may block.
                            val group = handlerExecutor
                            if (group == null) ch.pipeline().addLast(NettyEchoOrSink()) else ch.pipeline().addLast(group, NettyEchoOrSink())
                        }
                    },
                ).build()
        serverChannel =
            Bootstrap()
                .group(serverGroup)
                .channel(NioDatagramChannel::class.java)
                .handler(serverCodec)
                .bind(InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel()

        val clientCodec =
            QuicClientCodecBuilder()
                .sslContext(
                    QuicSslContextBuilder
                        .forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocols(NETTY_ALPN)
                        .build(),
                ).maxIdleTimeout(10, TimeUnit.MINUTES)
                .initialMaxData(64L * 1024 * 1024)
                .initialMaxStreamDataBidirectionalLocal(16L * 1024 * 1024)
                .initialMaxStreamDataBidirectionalRemote(16L * 1024 * 1024)
                .initialMaxStreamsBidirectional(1_000_000)
                .build()
        clientDatagram =
            Bootstrap()
                .group(clientGroup)
                .channel(NioDatagramChannel::class.java)
                .handler(clientCodec)
                .bind(InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel()
        connection =
            QuicChannel
                .newBootstrap(clientDatagram)
                .handler(
                    object : ChannelInitializer<QuicChannel>() {
                        override fun initChannel(ch: QuicChannel) {}
                    },
                ).remoteAddress(serverChannel.localAddress())
                .connect()
                .get(IO_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
    }

    /** A new bidirectional stream whose replies land in the returned sink. */
    fun openStream(): Pair<QuicStreamChannel, NettyStreamSink> {
        val sink = NettyStreamSink()
        val stream =
            connection
                .createStream(QuicStreamType.BIDIRECTIONAL, sink)
                .get(IO_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
        stream.config().isAllowHalfClosure = true
        return stream to sink
    }

    override fun close() {
        connection.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        clientDatagram.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        serverChannel.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS)
        serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS)
        handlerExecutor?.shutdownGracefully(0, 1, TimeUnit.SECONDS)?.awaitUninterruptibly(10, TimeUnit.SECONDS)
    }
}

private fun filledRequest(size: Int): ByteBuf {
    val buf = PooledByteBufAllocator.DEFAULT.directBuffer(size)
    buf.writeByte(ECHO_MARKER.toInt())
    repeat(size - 1) { buf.writeByte('r'.code) }
    return buf
}

/** [QuicLongLivedStreamRoundTrip] on Netty QUIC. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class NettyQuicLongLivedStreamRoundTrip {
    @Param("64", "1024")
    var size: Int = 0

    private lateinit var loopback: NettyQuicLoopback
    private lateinit var stream: QuicStreamChannel
    private lateinit var sink: NettyStreamSink
    private lateinit var request: ByteBuf

    @Setup(Level.Trial)
    fun setUp() {
        loopback = NettyQuicLoopback()
        val (s, k) = loopback.openStream()
        stream = s
        sink = k
        request = filledRequest(size)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        request.release()
        stream.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        loopback.close()
    }

    @Benchmark
    fun roundTrip(): Int {
        sink.reset()
        // The waiter is registered before the write so the reply cannot slip past it.
        stream.writeAndFlush(request.retainedDuplicate())
        return sink.awaitBytes(size)
    }
}

/** [QuicStreamPerRequest] on Netty QUIC: open, write 64 bytes, FIN, read the echo to the server's FIN, close. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class NettyQuicStreamPerRequest {
    private lateinit var loopback: NettyQuicLoopback
    private lateinit var request: ByteBuf

    @Setup(Level.Trial)
    fun setUp() {
        loopback = NettyQuicLoopback()
        request = filledRequest(SIZE)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        request.release()
        loopback.close()
    }

    @Benchmark
    fun requestResponse(): Int {
        val (stream, sink) = loopback.openStream()
        try {
            stream.writeAndFlush(request.retainedDuplicate()).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            return sink.awaitFinished()
        } finally {
            stream.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val SIZE = 64
    }
}

/** [QuicBulkThroughput] on Netty QUIC: 16 MiB per op in 64 KiB writes, FIN, wait for the server's FIN; score = MiB/s. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class NettyQuicBulkThroughput {
    private lateinit var loopback: NettyQuicLoopback
    private lateinit var chunk: ByteBuf

    @Setup(Level.Trial)
    fun setUp() {
        loopback = NettyQuicLoopback()
        // Filled once and re-sent through duplicates, as the QUIC benchmark rewinds one chunk.
        chunk = PooledByteBufAllocator.DEFAULT.directBuffer(CHUNK)
        repeat(CHUNK) { chunk.writeByte('D'.code) }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        chunk.release()
        loopback.close()
    }

    @Benchmark
    @OperationsPerInvocation(BULK_MIB)
    fun mibPerSecond(): Int {
        val (stream, sink) = loopback.openStream()
        try {
            // Netty queues writes without limit; the default 64 KiB high water mark would make every chunk wait
            // for the previous one to drain. 1-4 MiB lets writes pipeline behind flow control the way
            // writeFully's suspension does.
            stream.config().writeBufferWaterMark = WriteBufferWaterMark(1024 * 1024, 4 * 1024 * 1024)
            repeat(BULK_MIB * (1024 * 1024 / CHUNK)) {
                if (!stream.isWritable) sink.awaitWritable(stream)
                stream.writeAndFlush(chunk.retainedDuplicate())
            }
            stream.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
            return sink.awaitFinished()
        } finally {
            stream.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val BULK_MIB = 16
    }
}
