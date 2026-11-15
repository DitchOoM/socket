package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.toInetSocketAddress
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

/** A non-blocking [DatagramChannel], one datagram per system call. */
internal class NioOneByOne(
    val channel: DatagramChannel,
    send: SendBatch,
) : DatagramBatchIo {
    override val capability: BatchCapability = BatchCapability.OneByOne
    private val sendView: ByteBuffer = byteBufferOf(send.storage).duplicate()
    private val targets = HashMap<PathKey, InetSocketAddress>()

    override fun send(batch: SendBatch): SendBatchResult {
        val count = batch.pending
        for (n in 0 until count) {
            val offset = batch.offsetOf(n)
            sendView.limit(offset + batch.lengthOf(n)).position(offset)
            val written =
                try {
                    when (val target = batch.targetOf(n)) {
                        SendTarget.ConnectedPeer -> channel.write(sendView)
                        is SendTarget.ServerReply ->
                            if (target.to.family == NO_FAMILY) {
                                return SendBatchResult.Partial(n, UdpError.HostUnreachable)
                            } else {
                                channel.send(sendView, addressOf(target.to))
                            }
                    }
                } catch (e: IOException) {
                    return SendBatchResult.Partial(n, udpErrorOf(e))
                }
            if (written == 0) return SendBatchResult.Partial(n, UdpError.WouldBlock)
        }
        return SendBatchResult.AllTaken
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        var got = 0
        while (true) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            val bytes = byteBufferOf(buffer.buffer)
            bytes.clear()
            val from =
                try {
                    channel.receive(bytes)
                } catch (e: IOException) {
                    into.giveBack(buffer)
                    return if (got > 0) ReceiveBatchResult.Got(got) else ReceiveBatchResult.Failed(udpErrorOf(e))
                }
            if (from !is InetSocketAddress) {
                into.giveBack(buffer)
                break
            }
            into.received(buffer, bytes.position(), from.toPathKey(), ArrivedOn.Unreported)
            got++
        }
        return if (got > 0) ReceiveBatchResult.Got(got) else ReceiveBatchResult.Nothing
    }

    override fun close(): SocketClosed {
        channel.close()
        return SocketClosed
    }

    private fun addressOf(key: PathKey): InetSocketAddress {
        targets[key]?.let { return it }
        if (targets.size >= MAX_CACHED_TARGETS) targets.clear()
        // Only a key with an address family reaches here, and every such key converts.
        val address = key.toInetSocketAddress() as InetSocketAddress
        targets[key] = address
        return address
    }
}

private fun byteBufferOf(buffer: PlatformBuffer): ByteBuffer = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer

internal fun InetSocketAddress.toPathKey(): PathKey {
    // java.net.InetAddress hands its bytes out only as a ByteArray.
    @Suppress("NoByteArrayInProd")
    val bytes = address.address
    return if (address is Inet4Address) {
        PathKey(family = 4, port = port, hi = 0L, lo = bigEndian(bytes, 0, 4))
    } else {
        PathKey(family = 6, port = port, hi = bigEndian(bytes, 0, 8), lo = bigEndian(bytes, 8, 8))
    }
}

@Suppress("NoByteArrayInProd") // the java.net.InetAddress boundary above
private fun bigEndian(
    bytes: ByteArray,
    from: Int,
    count: Int,
): Long {
    var value = 0L
    for (i in from until from + count) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
    return value
}

/** NIO carries no errno: the type names some errors, the strerror phrase the rest. */
internal fun udpErrorOf(e: IOException): UdpError =
    when (e) {
        is PortUnreachableException -> UdpError.PortUnreachable
        is NoRouteToHostException -> UdpError.HostUnreachable
        is ClosedChannelException -> UdpError.SocketClosed
        else -> {
            val message = e.message.orEmpty()
            when {
                message.contains("No route to host") -> UdpError.HostUnreachable
                message.contains("Network is unreachable") -> UdpError.NetworkUnreachable
                message.contains("Message too long") -> UdpError.MessageTooLarge
                message.contains("No buffer space available") -> UdpError.NoBufferSpace
                message.contains("Permission denied") || message.contains("Operation not permitted") -> UdpError.PermissionDenied
                else -> UdpError.Other(e::class.simpleName.orEmpty())
            }
        }
    }

private const val MAX_CACHED_TARGETS = 4096

/** The family of a [PathKey] quiche could not decode. */
private const val NO_FAMILY = 0
