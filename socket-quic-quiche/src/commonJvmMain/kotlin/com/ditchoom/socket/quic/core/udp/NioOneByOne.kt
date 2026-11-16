package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.toInetSocketAddress
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * A non-blocking [DatagramChannel], one datagram per system call.
 *
 * Receiving is the loop's alone. Sending is safe from any thread, one thread per [SendBatch] at a time: what a send
 * touches besides the channel, which is thread-safe, is kept per lane. [sendViews] holds one view per lane, indexed
 * by [SendBatch.lane]; [receiveViews] one per receive slot, indexed by [ReceiveBuffer.index].
 */
@LoopOnly
internal class NioOneByOne(
    first: DatagramChannel,
    sendViews: List<JdkBytes>,
    private val receiveViews: List<JdkBytes>,
    /** Destinations whose [InetSocketAddress] each lane keeps; a server with more peers than this converts again past it. */
    private val maxCachedTargets: Int = MAX_CACHED_TARGETS,
) : DatagramBatchIo {
    override val capability: BatchCapability = BatchCapability.OneByOne
    private val cell = ChannelCell(first)
    private val sendBuffers: Array<ByteBuffer> = Array(sendViews.size) { sendViews[it].view.duplicate() }
    private val targets: Array<HashMap<PathKey, InetSocketAddress>> = Array(sendViews.size) { HashMap() }
    private var lastSender: Sender = Sender.None

    /** The channel in use now; [reopen] replaces it. */
    val channel: DatagramChannel get() = cell.load()

    override fun send(batch: SendBatch): SendBatchResult {
        val channel = cell.load()
        val sendView: Buffer = sendBuffers[batch.lane]
        val cache = targets[batch.lane]
        for (n in 0 until batch.pending) {
            val offset = batch.offsetOf(n)
            // Buffer's own methods: ByteBuffer's covariant overrides are absent from Android before API 33.
            sendView.limit(offset + batch.lengthOf(n)).position(offset)
            val written =
                try {
                    when (val target = batch.targetOf(n)) {
                        is SendTarget.ConnectedPeer -> channel.write(sendBuffers[batch.lane])
                        is SendTarget.ServerReply -> {
                            var address = cache[target.to]
                            if (address == null) {
                                address = target.to.toInetSocketAddress()
                                if (address == null) return SendBatchResult.Partial(n, UdpError.HostUnreachable)
                                if (cache.size >= maxCachedTargets) cache.clear()
                                cache[target.to] = address
                            }
                            channel.send(sendBuffers[batch.lane], address)
                        }
                    }
                } catch (e: IOException) {
                    return SendBatchResult.Partial(n, if (cell.replaced(channel)) UdpError.Replaced else udpErrorOf(e))
                }
            if (written == 0) return SendBatchResult.Partial(n, UdpError.WouldBlock)
        }
        return SendBatchResult.AllTaken
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        val channel = cell.load()
        var got = 0
        while (true) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            val bytes = receiveViews[buffer.index].view
            val asBuffer: Buffer = bytes
            asBuffer.clear()
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

    override fun probe(): SocketProbe = if (cell.load().isOpen) SocketProbe.Open else SocketProbe.Closed

    override fun reopen(): Reopened = cell.reopen()

    override fun moveTo(bind: PathKey): Moved =
        when (val address = bind.toInetSocketAddress()) {
            null -> Moved.Unsupported
            else -> cell.moveTo(address)
        }

    override fun close(): SocketClosed {
        cell.load().close()
        return SocketClosed
    }

    /** The JDK hands back the same sender object while one peer keeps sending; its key is converted once. */
    private fun keyOf(from: InetSocketAddress): PathKey =
        when (val last = lastSender) {
            is Sender.Seen ->
                if (last.address === from) {
                    last.key
                } else {
                    remember(from)
                }
            is Sender.None -> remember(from)
        }

    private fun remember(from: InetSocketAddress): PathKey {
        val seen = Sender.Seen(from, from.toPathKey())
        lastSender = seen
        return seen.key
    }
}

private sealed interface Sender {
    data object None : Sender

    class Seen(
        val address: InetSocketAddress,
        val key: PathKey,
    ) : Sender
}

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
