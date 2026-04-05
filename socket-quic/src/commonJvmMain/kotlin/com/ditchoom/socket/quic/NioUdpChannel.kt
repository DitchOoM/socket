package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/**
 * JVM [UdpChannel] backed by NIO [DatagramChannel] + [Selector].
 *
 * [receive] suspends on [Selector.select] — zero CPU when no packets arrive.
 * [send] writes directly (blocking, but kernel-buffered and fast for UDP).
 *
 * @param peerAddr If non-null, uses `channel.send(buf, peerAddr)` (unconnected server socket).
 *                 If null, uses `channel.write(buf)` (connected client socket).
 */
internal class NioUdpChannel(
    private val channel: DatagramChannel,
    private val peerAddr: InetSocketAddress? = null,
) : UdpChannel {
    private val selector: Selector? =
        if (peerAddr == null) {
            Selector.open().also { channel.register(it, SelectionKey.OP_READ) }
        } else {
            null // Server-mode: no selector needed (packets arrive via server's central loop)
        }

    override suspend fun receive(buffer: PlatformBuffer): Int {
        val sel = selector ?: throw UnsupportedOperationException("Server-mode UdpChannel does not support receive")
        sel.select()
        sel.selectedKeys().clear()

        val bb = (buffer.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
        bb.clear()
        return channel.read(bb)
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
    ) {
        val bb = (buffer.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
        bb.clear()
        bb.limit(len)
        if (peerAddr != null) {
            channel.send(bb, peerAddr)
        } else {
            channel.write(bb)
        }
    }

    override fun close() {
        try {
            selector?.wakeup()
            selector?.close()
        } catch (_: Exception) {
        }
        try {
            channel.close()
        } catch (_: Exception) {
        }
    }
}
