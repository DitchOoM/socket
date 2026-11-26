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
 * A non-blocking [DatagramChannel], one datagram per system call, and the second one beside it while a client moves.
 *
 * Receiving is the loop's alone. Sending is safe from any thread, one thread per [SendBatch] at a time: what a send
 * touches besides the channel, which is thread-safe, is kept per lane. [sends] holds a view of one batch per lane,
 * indexed by [SendBatch.lane]; a lane whose batch is another one now (the loop's lane, after an endpoint kept the loop's
 * batch) is viewed again. [receiveViews] holds one view per receive slot, indexed by [ReceiveBuffer.index].
 */
@LoopOnly
internal class NioOneByOne(
    first: DatagramChannel,
    sends: List<SendView>,
    private val receiveViews: List<JdkBytes>,
    /**
     * Destinations whose [InetSocketAddress] each lane keeps, and senders whose [PathKey] the receive keeps; a server
     * with more peers than this converts again past it.
     */
    private val maxCachedTargets: Int = MAX_CACHED_TARGETS,
) : DatagramBatchIo {
    override val capability: BatchCapability = BatchCapability.OneByOne
    private val cell = ChannelCell(first)
    private val sendStorages: Array<NativeBytes> = Array(sends.size) { sends[it].storage }
    private val sendBuffers: Array<ByteBuffer> = Array(sends.size) { sends[it].bytes.view.duplicate() }
    private val targets: Array<HashMap<PathKey, InetSocketAddress>> = Array(sends.size) { HashMap() }
    private val senders = HashMap<InetSocketAddress, PathKey>()

    /** The channel in use now; [reopen] replaces it. */
    val channel: DatagramChannel get() = cell.load()

    override fun send(batch: SendBatch): SendBatchResult {
        val channels = cell.channels()
        val channel = channels.held.channel()
        if (sendStorages[batch.lane] !== batch.storage) {
            when (val backed = JdkBytes.of(batch.storage)) {
                is JdkBacked.Backed -> sendBuffers[batch.lane] = backed.bytes.view.duplicate()
                // The loop's spares are refused unless a JDK channel can write them: no datagram can leave from here.
                is JdkBacked.NotJdkBacked -> return SendBatchResult.Partial(0, UdpError.NoBufferSpace)
            }
            sendStorages[batch.lane] = batch.storage
        }
        val sendView: Buffer = sendBuffers[batch.lane]
        val cache = targets[batch.lane]
        for (n in 0 until batch.pending) {
            val offset = batch.offsetOf(n)
            // Buffer's own methods: ByteBuffer's covariant overrides are absent from Android before API 33.
            sendView.limit(offset + batch.lengthOf(n)).position(offset)
            val ticket = batch.socketOf(n)
            if (ticket != channels.ticket) {
                sendBeside(channels.second, ticket, sendBuffers[batch.lane])
                continue
            }
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

    /** One datagram for the socket [ticket] names, if it is the second: best effort, so a refusal drops it. */
    private fun sendBeside(
        second: SecondChannel,
        ticket: SocketTicket,
        bytes: ByteBuffer,
    ) {
        when (second) {
            is SecondChannel.Open ->
                if (second.ticket == ticket) {
                    try {
                        second.channel.write(bytes)
                    } catch (dropped: IOException) {
                        // The probe's socket refused it, or was closed under the send: the datagram is lost, as on a link.
                    }
                }
            is SecondChannel.None -> Unit
        }
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult = receiveFrom(cell.load(), into, Refusal.Reported)

    override fun receiveSecond(into: ReceiveBatch): ReceiveBatchResult =
        when (val second = cell.second()) {
            is SecondChannel.Open -> receiveFrom(second.channel, into, Refusal.ReadAsNothing)
            is SecondChannel.None -> ReceiveBatchResult.Nothing
        }

    private fun receiveFrom(
        channel: DatagramChannel,
        into: ReceiveBatch,
        refusal: Refusal,
    ): ReceiveBatchResult {
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
                    return when {
                        got > 0 -> ReceiveBatchResult.Got
                        refusal == Refusal.ReadAsNothing -> ReceiveBatchResult.Nothing
                        else -> ReceiveBatchResult.Failed(udpErrorOf(e))
                    }
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

    override fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside =
        when (val address = bind.toInetSocketAddress()) {
            null -> Beside.Unsupported
            else -> cell.openBeside(address, ticket)
        }

    override fun reopenSecond(): Reopened = cell.reopenSecond()

    override fun promote() = cell.promote()

    override fun closeSecond() = cell.closeSecond()

    /** The second channel, which the selector watches while one is open. */
    val secondChannel: DatagramChannel get() = cell.secondChannel()

    override fun close(): SocketClosed {
        cell.closeSecond()
        cell.load().close()
        return SocketClosed
    }

    /**
     * The key of a sender heard before is the one made the first time: peers take turns on a server's socket, and the
     * JDK makes a new address object for each sender that is not the previous one, so the lookup is by equality.
     */
    private fun keyOf(from: InetSocketAddress): PathKey {
        val known = senders[from]
        if (known != null) return known
        if (senders.size >= maxCachedTargets) senders.clear()
        val key = from.toPathKey()
        senders[from] = key
        return key
    }
}

/** What a receive makes of a channel's refusal: the carrying socket's is reported, a probe's is read as nothing waiting. */
private enum class Refusal { Reported, ReadAsNothing }

/** A lane's batch memory and the JDK view of it a channel writes from. */
internal class SendView(
    val storage: NativeBytes,
    val bytes: JdkBytes,
)

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
