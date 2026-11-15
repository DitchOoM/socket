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
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

/**
 * A non-blocking [DatagramChannel], one datagram per system call.
 *
 * Sending is safe from any thread, one thread per [SendBatch] at a time: what a send touches besides the channel,
 * which is thread-safe, is kept per batch lane. [batches] are every batch it may be asked to send, the loop's
 * first; a batch's [SendBatch.lane] is its place in that list. Receiving is the loop's alone.
 */
internal class NioOneByOne(
    val channel: DatagramChannel,
    batches: List<SendBatch>,
    /** Destinations whose [InetSocketAddress] each lane keeps; a server with more peers than this converts again past it. */
    private val maxCachedTargets: Int = MAX_CACHED_TARGETS,
) : DatagramBatchIo {
    constructor(channel: DatagramChannel, send: SendBatch) : this(channel, listOf(send))

    override val capability: BatchCapability = BatchCapability.OneByOne
    private val sendViews: Array<ByteBuffer> = Array(batches.size) { byteBufferOf(batches[it].storage).duplicate() }
    private val targets: Array<HashMap<PathKey, InetSocketAddress>> = Array(batches.size) { HashMap() }
    private var lastSender: Sender = Sender.None

    override fun send(batch: SendBatch): SendBatchResult {
        val count = batch.pending
        val sendView = sendViews[batch.lane]
        for (n in 0 until count) {
            val offset = batch.offsetOf(n)
            // Buffer's own methods: ByteBuffer's covariant overrides are absent from Android before API 33.
            (sendView as Buffer).limit(offset + batch.lengthOf(n)).position(offset)
            val written =
                try {
                    when (val target = batch.targetOf(n)) {
                        SendTarget.ConnectedPeer -> channel.write(sendView)
                        is SendTarget.ServerReply ->
                            if (target.to.family == NO_FAMILY) {
                                return SendBatchResult.Partial(n, UdpError.HostUnreachable)
                            } else {
                                channel.send(sendView, addressOf(batch.lane, target.to))
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
            (bytes as Buffer).clear()
            val from =
                try {
                    channel.receive(bytes)
                } catch (e: IOException) {
                    into.giveBack(buffer)
                    return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Failed(udpErrorOf(e))
                }
            if (from !is InetSocketAddress) {
                into.giveBack(buffer)
                break
            }
            into.received(buffer, bytes.position(), keyOf(from), ArrivedOn.Unreported)
            got++
        }
        return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
    }

    override fun close(): SocketClosed {
        channel.close()
        return SocketClosed
    }

    /** The JDK hands back the same sender object while one peer keeps sending; its key is converted once. */
    private fun keyOf(from: InetSocketAddress): PathKey {
        val last = lastSender
        if (last is Sender.Seen && last.address === from) return last.key
        val seen = Sender.Seen(from, from.toPathKey())
        lastSender = seen
        return seen.key
    }

    private fun addressOf(
        lane: Int,
        key: PathKey,
    ): InetSocketAddress {
        val cache = targets[lane]
        cache[key]?.let { return it }
        if (cache.size >= maxCachedTargets) cache.clear()
        // Only a key with an address family reaches here, and every such key converts.
        val address = key.toInetSocketAddress() as InetSocketAddress
        cache[key] = address
        return address
    }
}

private sealed interface Sender {
    object None : Sender

    class Seen(
        val address: InetSocketAddress,
        val key: PathKey,
    ) : Sender
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

/** NIO carries no errno: the exception's type names the errors it can, and any other is [UdpError.Other] with the exception. */
internal fun udpErrorOf(e: IOException): UdpError =
    when (e) {
        is PortUnreachableException -> UdpError.PortUnreachable
        is NoRouteToHostException -> UdpError.HostUnreachable
        is ClosedChannelException -> UdpError.SocketClosed
        else -> UdpError.Other(e)
    }

internal const val MAX_CACHED_TARGETS = 4096

/** The family of a [PathKey] quiche could not decode. */
private const val NO_FAMILY = 0
