@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.NO_DATAGRAM_ROOM
import com.ditchoom.socket.quic.QuicheSendStop
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/** Whether a feed moved any datagram into quiche. */
internal enum class Refill { Moved, Nothing }

/** What quiche did with one datagram handed to it. */
private enum class HandOff { Took, Full, Dropped }

/** Whether the door holds the datagram quiche last refused for a full queue. */
private enum class Door { Empty, Holding }

/**
 * A connection's RFC 9221 sends, inside it. The [queue] any thread offers to is the queue its options bound; quiche's own send queue is a short hand-off behind it,
 * fed from the queue's head whenever quiche has room: when the connection is served, and each time quiche's output runs
 * dry in a write. A datagram past the outgoing age at the head is dropped and counted; the one quiche refused last for
 * a full queue waits at the door, copied out of its cell, and goes first next time. Callers waiting for room are woken
 * in the order they began to wait once the queue is at its low-water mark.
 */
@Confined
internal class DatagramSender(
    private val q: QuicheConnRef,
    val queue: DatagramQueue,
    private val outgoing: AgeBound,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private val room = AtomicInt(NO_DATAGRAM_ROOM)
    private val waiting = ArrayDeque<RoomWait>()
    private var door = Door.Empty
    private var doorMemory: CellMemory = CellMemory.Unmade
    private var doorLength = 0
    private var doorQueuedAt = 0L

    /** Monotonic: datagrams quiche took to send. */
    var output = 0L
        private set

    /** Any thread: the bytes one datagram may carry as of the connection's last turn; [NO_DATAGRAM_ROOM] when none can be sent. */
    @Published
    fun room(): Int = room.load()

    /** Reads the room a datagram has now. */
    fun readRoom() = room.store(q.api.connDgramMaxWritableBytes(q.handle))

    /** Moves datagrams from the queue's head into quiche until quiche's queue is full or this one is empty, at [now]. */
    fun feed(now: Nanos): Refill {
        if (door == Door.Empty && queue.size() == 0) return Refill.Nothing
        var moved = Refill.Nothing
        when (door) {
            Door.Empty -> Unit
            Door.Holding ->
                when (fromDoor(now)) {
                    HandOff.Took -> moved = Refill.Moved
                    HandOff.Dropped -> Unit
                    HandOff.Full -> return moved
                }
        }
        while (true) {
            val cell =
                when (val head = queue.take()) {
                    is Head.Empty -> break
                    is QueuedDatagram -> head
                }
            val handed =
                when (val memory = cell.memory) {
                    is CellMemory.Unmade -> HandOff.Dropped
                    is CellMemory.Made ->
                        when (cell.fill) {
                            Fill.Nothing -> HandOff.Dropped
                            Fill.Datagram -> handOff(memory.bytes.address, cell.length, cell.queuedAt, now)
                        }
                }
            when (handed) {
                HandOff.Took -> moved = Refill.Moved
                HandOff.Dropped -> Unit
                HandOff.Full -> keepAtDoor(cell)
            }
            queue.release(cell)
            if (handed == HandOff.Full) break
        }
        wakeIfLow()
        return moved
    }

    /** [wait] is answered once the queue is at its low-water mark: now, or when a feed drains it there. */
    fun awaitRoom(wait: RoomWait) {
        if (queue.size() <= queue.lowWater) wait.room() else waiting.addLast(wait)
    }

    fun withdraw(wait: RoomWait) {
        waiting.remove(wait)
    }

    /** The connection ended: the queue takes nothing more and its memory goes, and every waiting caller is answered. */
    fun endAll() {
        queue.close()
        when (val memory = doorMemory) {
            is CellMemory.Made -> memory.bytes.free()
            is CellMemory.Unmade -> Unit
        }
        doorMemory = CellMemory.Unmade
        door = Door.Empty
        while (waiting.isNotEmpty()) waiting.removeFirst().room()
    }

    private fun wakeIfLow() {
        if (waiting.isEmpty() || queue.size() > queue.lowWater) return
        while (waiting.isNotEmpty()) waiting.removeFirst().room()
    }

    private fun handOff(
        address: Long,
        length: Int,
        queuedAt: Long,
        now: Nanos,
    ): HandOff {
        if (outgoing.expired(queuedAt, now.value) == Expiry.Expired) {
            counts.datagramExpiredOutgoing()
            return HandOff.Dropped
        }
        val sent = q.api.connDgramSend(q.handle, if (length > 0) address else 0L, length)
        return when {
            sent >= 0 -> {
                output++
                HandOff.Took
            }
            sent == QUICHE_ERR_DONE -> HandOff.Full
            else -> {
                if (sent == QuicheSendStop.QUICHE_ERR_BUFFER_TOO_SHORT) readRoom()
                counts.datagramDroppedUnsendable()
                HandOff.Dropped
            }
        }
    }

    /** Copies [cell]'s datagram to the door, whose memory is made the first time and grown when it is too small. */
    private fun keepAtDoor(cell: QueuedDatagram) {
        val memory = cell.memory
        if (memory !is CellMemory.Made) return
        val held = doorMemory
        val into =
            if (held is CellMemory.Made && held.bytes.capacity >= cell.length) {
                held
            } else {
                if (held is CellMemory.Made) held.bytes.free()
                made(cell.length)
            }
        doorMemory = into
        when (into) {
            is CellMemory.Unmade -> counts.datagramDroppedUnsendable()
            is CellMemory.Made -> {
                memory.bytes.copyOut(into.bytes.address, cell.length)
                doorLength = cell.length
                doorQueuedAt = cell.queuedAt
                door = Door.Holding
            }
        }
    }

    private fun made(length: Int): CellMemory {
        val buffer = factory.allocate(maxOf(length, DATAGRAM_ROOM))
        return when (val made = NativeBytes.of(buffer)) {
            is Addressed.Native -> CellMemory.Made(made.bytes)
            is Addressed.NotAddressable -> {
                buffer.freeNativeMemory()
                CellMemory.Unmade
            }
        }
    }

    /** Hands quiche the datagram at the door; the door is empty after unless quiche's queue is still full. */
    private fun fromDoor(now: Nanos): HandOff {
        val handed =
            when (val memory = doorMemory) {
                is CellMemory.Made -> handOff(memory.bytes.address, doorLength, doorQueuedAt, now)
                is CellMemory.Unmade -> HandOff.Dropped
            }
        if (handed != HandOff.Full) door = Door.Empty
        return handed
    }
}

/**
 * A caller waiting in [DatagramSend.QueueFull.awaitRoom][com.ditchoom.socket.quic.DatagramSend.QueueFull.awaitRoom],
 * parked inside the connection until its send queue is at its low-water mark or the connection ends. Answered once.
 */
internal class RoomWait(
    private val caller: CancellableContinuation<Unit>,
) : ConnWork() {
    override fun run(inside: Inside) =
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> room()
            is Datagrams.On -> datagrams.out.awaitRoom(this)
        }

    override fun gone(end: ConnEnd) = room()

    /** The caller goes on; one cancelled meanwhile is not resumed. */
    fun room() = caller.resume(Unit)
}

/** Takes back a [RoomWait] whose caller was cancelled. */
internal class RoomWithdraw(
    private val wait: RoomWait,
) : ConnWork() {
    override fun run(inside: Inside) =
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> Unit
            is Datagrams.On -> datagrams.out.withdraw(wait)
        }

    override fun gone(end: ConnEnd) = Unit
}

/** Serves the connection after a send queued into an idle queue: its datagrams go to quiche. One per queue, reused. */
internal class FeedWork(
    private val queue: DatagramQueue,
) : ConnWork() {
    override fun run(inside: Inside) {
        queue.fed()
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> Unit
            is Datagrams.On -> datagrams.out.feed(inside.now())
        }
    }

    override fun gone(end: ConnEnd) = queue.fed()
}
