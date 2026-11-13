package com.ditchoom.socket.quic

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.quic.InsecureQuicTokenHandler
import io.netty.handler.codec.quic.QuicChannel
import io.netty.handler.codec.quic.QuicPathEvent
import io.netty.handler.codec.quic.QuicServerCodecBuilder
import io.netty.handler.codec.quic.QuicSslContextBuilder
import io.netty.handler.codec.quic.QuicStreamChannel
import java.io.File
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * A QUIC echo server this library did not write: Netty's QUIC codec on `127.0.0.1`, with active migration
 * permitted. Every stream echoes what it reads. It records the path events Netty reports and the
 * connections it accepted, so a test can ask what the server saw of a client's migration.
 */
internal class NettyQuicEchoServer(
    certChain: File,
    privateKey: File,
    alpn: String,
) : AutoCloseable {
    private val group: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val pathEvents = ConcurrentLinkedQueue<QuicPathEvent>()
    private val connections = ConcurrentLinkedQueue<QuicChannel>()
    private val datagrams: Channel

    init {
        val codec =
            QuicServerCodecBuilder()
                .sslContext(QuicSslContextBuilder.forServer(privateKey, null, certChain).applicationProtocols(alpn).build())
                .maxIdleTimeout(IDLE_SECONDS, TimeUnit.SECONDS)
                .initialMaxData(CONNECTION_WINDOW)
                .initialMaxStreamDataBidirectionalLocal(STREAM_WINDOW)
                .initialMaxStreamDataBidirectionalRemote(STREAM_WINDOW)
                .initialMaxStreamsBidirectional(MAX_STREAMS)
                .activeMigration(true)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(
                    object : ChannelInitializer<QuicChannel>() {
                        override fun initChannel(ch: QuicChannel) {
                            connections.add(ch)
                            ch.pipeline().addLast(PathEventRecorder(pathEvents))
                        }
                    },
                ).streamHandler(
                    object : ChannelInitializer<QuicStreamChannel>() {
                        override fun initChannel(ch: QuicStreamChannel) {
                            ch.pipeline().addLast(Echo())
                        }
                    },
                ).build()
        datagrams =
            try {
                Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel::class.java)
                    .handler(codec)
                    .bind(InetSocketAddress("127.0.0.1", 0))
                    .sync()
                    .channel()
            } catch (failed: Throwable) {
                stopThreads()
                throw failed
            }
    }

    val port: Int get() = (datagrams.localAddress() as InetSocketAddress).port

    /** The path events Netty has reported so far, oldest first. */
    fun pathEvents(): List<QuicPathEvent> = pathEvents.toList()

    /** The peer Netty currently names for each connection it accepted. */
    fun peers(): List<NettyPeer> = connections.map { NettyPeer.of(it.remoteSocketAddress()) }

    /** Closes the socket and ends every thread the server started, whatever the close of the socket did. */
    override fun close() {
        try {
            datagrams.close().sync()
        } finally {
            stopThreads()
        }
    }

    private fun stopThreads() {
        group.shutdownGracefully(0, SHUTDOWN_SECONDS, TimeUnit.SECONDS).sync()
    }

    /** What Netty names as a connection's peer. */
    sealed interface NettyPeer {
        data class At(
            val port: Int,
        ) : NettyPeer

        /** The connection has no peer address any more (it closed), or one that is not an IP address. */
        data class NotAnIpPeer(
            val address: String,
        ) : NettyPeer

        companion object {
            fun of(address: SocketAddress?): NettyPeer =
                when (address) {
                    is InetSocketAddress -> At(address.port)
                    else -> NotAnIpPeer(address.toString())
                }
        }
    }

    private class PathEventRecorder(
        private val events: ConcurrentLinkedQueue<QuicPathEvent>,
    ) : ChannelInboundHandlerAdapter() {
        override fun userEventTriggered(
            ctx: ChannelHandlerContext,
            evt: Any,
        ) {
            if (evt is QuicPathEvent) events.add(evt)
            ctx.fireUserEventTriggered(evt)
        }
    }

    private class Echo : ChannelInboundHandlerAdapter() {
        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            ctx.write(msg)
        }

        override fun channelReadComplete(ctx: ChannelHandlerContext) {
            ctx.flush()
        }
    }

    private companion object {
        const val IDLE_SECONDS = 60L
        const val SHUTDOWN_SECONDS = 2L
        const val CONNECTION_WINDOW = 16L * 1024 * 1024
        const val STREAM_WINDOW = 1024L * 1024
        const val MAX_STREAMS = 16L
    }
}
