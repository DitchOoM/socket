package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.runInterruptible
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/**
 * JVM [UdpChannel] backed by NIO [DatagramChannel] + [Selector].
 *
 * [receive] uses [runInterruptible] around [Selector.select] — zero CPU
 * when no packets arrive, and coroutine cancellation propagates correctly
 * (interrupts the underlying thread, which causes `select()` to return).
 *
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
        // Selector.select() is a blocking JDK call — declaring this function
        // suspend was a lie that cost us: coroutine cancellation could not
        // reach a thread blocked in EPoll.wait, so when upstream cleanup
        // hung at quicConnection.close() (deferred await on a stuck driver),
        // udpChannel.close() never ran and udpReaderLoop coroutines leaked
        // forever, holding their Dispatchers.Default worker thread each.
        // After ~150 tests on a 2-CPU GH runner the Default pool was
        // exhausted and the next handshake never got a worker. Diagnosed
        // from the CI dump on commit 2878a82 (run 26522427351).
        //
        // runInterruptible runs the blocking call on quicBlockingDispatcher
        // (virtual threads on JDK 21+, Dispatchers.IO fallback otherwise)
        // and, on coroutine cancellation, interrupts the underlying thread.
        // Selector.select() respects thread interrupt and returns 0, then
        // the next iteration of the caller's loop sees isActive == false
        // and exits cleanly.
        runInterruptible(quicBlockingDispatcher) { sel.select() }
        sel.selectedKeys().clear()

        val bb = (buffer.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
        bb.clear()
        return channel.read(bb)
    }

    // 1-entry cache for the last sendInfo.to reconstruction. Server egress targets the same
    // destination on consecutive datagrams in steady state, so this keeps the common path free
    // of per-datagram InetSocketAddress/InetAddress allocation; it only rebuilds when the peer's
    // address actually changes (i.e. a migration).
    private var lastDestKey: PathKey? = null
    private var lastDestAddr: InetSocketAddress? = null

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        val bb = (buffer.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
        bb.clear()
        bb.limit(len)
        val target =
            if (dest != null) {
                if (dest != lastDestKey) {
                    lastDestAddr = dest.toInetSocketAddress()
                    lastDestKey = dest
                }
                lastDestAddr ?: peerAddr
            } else {
                peerAddr
            }
        if (target != null) {
            channel.send(bb, target)
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
