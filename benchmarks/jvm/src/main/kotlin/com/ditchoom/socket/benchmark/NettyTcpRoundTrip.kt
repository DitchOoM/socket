package com.ditchoom.socket.benchmark

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

private class NettyTcpEcho : ChannelInboundHandlerAdapter() {
    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        ctx.write(msg)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
        ctx.fireChannelReadComplete()
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        ctx.close()
    }
}

/**
 * [TcpRoundTrip] on Netty's NIO transport: the same long-lived echo of [size] bytes over 127.0.0.1, one event
 * loop for the server and one for the client. TCP_NODELAY on both ends, as a latency-minded Netty application
 * sets it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class NettyTcpRoundTrip {
    @Param("64", "1024")
    var size: Int = 0

    private val serverGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val clientGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private lateinit var server: Channel
    private lateinit var client: Channel
    private lateinit var sink: NettyStreamSink
    private lateinit var request: ByteBuf

    @Setup(Level.Trial)
    fun setUp() {
        server =
            ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast(NettyTcpEcho())
                        }
                    },
                ).bind(InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel()
        sink = NettyStreamSink()
        client =
            Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(sink)
                .connect(server.localAddress())
                .sync()
                .channel()
        request = PooledByteBufAllocator.DEFAULT.directBuffer(size)
        repeat(size) { request.writeByte('r'.code) }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        request.release()
        client.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        server.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS)
        serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS)
    }

    @Benchmark
    fun roundTrip(): Int {
        sink.reset()
        client.writeAndFlush(request.retainedDuplicate())
        return sink.awaitBytes(size)
    }
}
