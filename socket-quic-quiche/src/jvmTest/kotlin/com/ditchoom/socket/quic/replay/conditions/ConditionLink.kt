package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import java.nio.Buffer
import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Which end of the link. */
internal enum class Side { Client, Server }

/** What the OS says now, as the link sees it. */
internal sealed interface OsNow {
    data object Offline : OsNow

    data class On(
        val network: NetHandle,
    ) : OsNow
}

/**
 * The network between a replayed client and server, run by [Conditions]: each datagram is lost or delayed as the
 * window it is sent in says, from one seeded random stream, and nothing crosses an outage. The client's socket is tied
 * to the network it was opened on, as Android ties it: once the OS has another network up (or none), the socket takes
 * what is sent and delivers nothing, until [DatagramBatchIo.reopen] opens it on the network up now. Loop thread only.
 */
internal class ConditionLink(
    private val conditions: Conditions,
    seed: Long,
    private val clock: LoopClock,
) {
    /** Where each end's datagrams come from, as the other end's socket reports them; set once both are bound. */
    var client = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    var server = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)

    private class InFlight(
        val arrives: Long,
        val order: Long,
        val to: Side,
        val bytes: ByteArray,
    )

    private val random = Random(seed)
    private val origin = clock.now().value
    private val inFlight = PriorityQueue<InFlight>(compareBy<InFlight>({ it.arrives }, { it.order }))
    private val waiting = mapOf(Side.Client to ArrayDeque<ByteArray>(), Side.Server to ArrayDeque())
    private var order = 0L
    private var socketHome: NetHandle = conditions.start.network
    var os: OsNow = OsNow.On(conditions.start.network)

    /** Every datagram either end sent or the link delivered: what the replay watches to know it has settled. */
    var moved = 0L
        private set
    var reopens = 0
        private set

    val clientIo: DatagramBatchIo = End(Side.Client)
    val serverIo: DatagramBatchIo = End(Side.Server)

    /** The next arrival's time, or [Long.MAX_VALUE]. */
    fun nextArrival(): Long = if (inFlight.isEmpty()) Long.MAX_VALUE else inFlight.peek().arrives

    /** Hands each datagram whose time has come to the socket it was sent to. */
    fun deliverDue(now: Long) {
        while (inFlight.isNotEmpty() && inFlight.peek().arrives <= now) {
            val datagram = inFlight.poll()
            moved++
            if (windowAt(datagram.arrives) is LinkWindow.Outage) continue
            if (datagram.to == Side.Client && clientSocketDead()) continue
            waiting.getValue(datagram.to).addLast(datagram.bytes)
        }
    }

    private fun send(
        from: Side,
        bytes: ByteArray,
        now: Long,
    ) {
        moved++
        if (from == Side.Client && clientSocketDead()) return
        val window = windowAt(now)
        val draw = random.nextInt(PER_MILLE)
        val delay =
            when (window) {
                is LinkWindow.Outage -> return
                is LinkWindow.Flowing -> {
                    val loss = if (from == Side.Client) window.lossToPeer else window.lossFromPeer
                    if (draw < loss.value) return
                    val span = (window.delay.max - window.delay.min).inWholeNanoseconds
                    window.delay.min.inWholeNanoseconds + if (span > 0) random.nextLong(span + 1) else 0L
                }
            }
        val to = if (from == Side.Client) Side.Server else Side.Client
        inFlight.add(InFlight(now + delay, order++, to, bytes))
    }

    private fun clientSocketDead(): Boolean =
        when (val now = os) {
            is OsNow.Offline -> true
            is OsNow.On -> now.network != socketHome
        }

    private fun windowAt(nanos: Long): LinkWindow {
        val at: Duration = (nanos - origin).nanoseconds
        return conditions.link
            .filter { at >= it.from }
            .ifEmpty { conditions.link.take(1) }
            .last()
    }

    /** One end's socket. */
    private inner class End(
        private val side: Side,
    ) : DatagramBatchIo {
        override val capability: BatchCapability = BatchCapability.OneByOne

        override fun send(batch: SendBatch): SendBatchResult {
            val view = (batch.storage.owner.unwrapFully() as BaseJvmBuffer).byteBuffer.duplicate()
            for (n in 0 until batch.pending) {
                (view as Buffer).limit(batch.offsetOf(n) + batch.lengthOf(n)).position(batch.offsetOf(n))
                val bytes = ByteArray(batch.lengthOf(n))
                view.get(bytes)
                send(side, bytes, clock.now().value)
            }
            return SendBatchResult.AllTaken
        }

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            val queue = waiting.getValue(side)
            var got = 0
            while (queue.isNotEmpty()) {
                val buffer = into.take()
                if (buffer !is ReceiveBuffer) break
                val bytes = queue.removeFirst()
                val view = (buffer.bytes.owner.unwrapFully() as BaseJvmBuffer).byteBuffer
                (view as Buffer).clear()
                view.put(bytes)
                into.received(buffer, bytes.size, if (side == Side.Client) server else client, ArrivedOn.Unreported)
                got++
            }
            return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
        }

        override fun probe(): SocketProbe = SocketProbe.Open

        override fun reopen(): Reopened {
            reopens++
            when (val now = os) {
                is OsNow.On -> socketHome = now.network
                is OsNow.Offline -> Unit
            }
            return Reopened.SameEndpoint
        }

        override fun close(): SocketClosed = SocketClosed
    }

    private companion object {
        const val PER_MILLE = 1000
    }
}
