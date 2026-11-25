@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.StreamRoom
import com.ditchoom.socket.quic.StreamSend
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.SendRefusal
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a stream's send direction stands for its outbox. */
internal sealed interface OutboxState {
    data object Open : OutboxState

    /** Our FIN is queued behind the bytes before it. */
    data object FinishAsked : OutboxState

    /** quiche took our FIN. */
    data object FinTaken : OutboxState

    /** This side reset the direction. */
    data object ResetHere : OutboxState

    /** The peer stopped the direction, or the connection ended it. */
    class Over(
        val why: StreamSend.NotSent.Over,
    ) : OutboxState
}

/** What a sender waits for: room in the queue, or our FIN taken by quiche. */
internal enum class OutboxWants { Room, FinTaken }

/** The one sender's wait, if it waits. */
internal sealed interface OutboxParked {
    data object Nobody : OutboxParked

    class Waiting(
        val caller: CancellableContinuation<StreamRoom>,
        val wants: OutboxWants,
    ) : OutboxParked
}

/** Whether a wait is answered now, and with what. */
internal sealed interface OutboxReadiness {
    data object NotYet : OutboxReadiness

    class Now(
        val room: StreamRoom,
    ) : OutboxReadiness
}

internal val OUTBOX_ROOM_NOW = OutboxReadiness.Now(StreamRoom.Room)

/** The queue's memory: made on the first send, so a stream that sends nothing holds none. */
internal sealed interface OutboxRing {
    data object None : OutboxRing

    class Held(
        val buffer: PlatformBuffer,
        val address: Long,
    ) : OutboxRing
}

/** Copies [taking] bytes of [bytes] into the ring at the queue's [queued] count, wrapping at its end. */
internal fun OutboxRing.Held.copyIn(
    bytes: ReadBuffer,
    queued: Long,
    taking: Int,
) {
    val at = (queued % OUTBOX_CAPACITY).toInt()
    val first = minOf(taking, OUTBOX_CAPACITY - at)
    copyIntoRing(bytes, buffer, at, first)
    if (taking > first) copyIntoRing(bytes, buffer, 0, taking - first)
}

/** Copies [length] bytes of [from] to [into] at [at], moving [from] past them; [into]'s position is the sender's own. */
internal fun copyIntoRing(
    from: ReadBuffer,
    into: PlatformBuffer,
    at: Int,
    length: Int,
) {
    val limit = from.limit()
    from.setLimit(from.position() + length)
    into.position(at)
    into.setLimit(at + length)
    into.write(from)
    from.setLimit(limit)
}

/** What a wait for [wants] answers in [state], with [roomy] whether the queue is down to its low-water mark. */
internal fun readiness(
    state: OutboxState,
    wants: OutboxWants,
    roomy: Boolean,
): OutboxReadiness =
    when (state) {
        is OutboxState.Over -> OutboxReadiness.Now(StreamRoom.Over(state.why))
        is OutboxState.ResetHere -> OutboxReadiness.Now(StreamRoom.Over(StreamSend.NotSent.Finished))
        is OutboxState.FinTaken -> OUTBOX_ROOM_NOW
        is OutboxState.Open, is OutboxState.FinishAsked ->
            when (wants) {
                OutboxWants.Room -> if (roomy) OUTBOX_ROOM_NOW else OutboxReadiness.NotYet
                OutboxWants.FinTaken -> OutboxReadiness.NotYet
            }
    }

/** A queue's memory of [capacity] bytes from [factory], which the connect checked makes memory with an address. */
internal fun ringOf(
    factory: BufferFactory,
    capacity: Int,
): OutboxRing.Held {
    val buffer = factory.allocate(capacity)
    val address =
        when (val at = NativeBytes.addressOf(buffer)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> {
                buffer.freeNativeMemory()
                raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.Ended)
            }
        }
    return OutboxRing.Held(buffer, address)
}

/** The sender's queue memory, made on its first use. */
internal fun AtomicReference<OutboxRing>.held(factory: BufferFactory): OutboxRing.Held =
    when (val now = load()) {
        is OutboxRing.Held -> now
        is OutboxRing.None -> ringOf(factory, OUTBOX_CAPACITY).also { store(it) }
    }

/** The queue's memory goes back to the connection's factory, once. */
internal fun AtomicReference<OutboxRing>.letGo() {
    when (val now = load()) {
        is OutboxRing.Held -> if (compareAndSet(now, OutboxRing.None)) now.buffer.freeIfNeeded()
        is OutboxRing.None -> Unit
    }
}

/** The end a refused send reports. */
internal fun SendRefusal.end(): ConnEnd =
    when (this) {
        is SendRefusal.PastClose -> end
        is SendRefusal.Ended -> end
    }

/** Told on the loop when a stream's send queue handed quiche bytes: the stream's receiver, holding a reply it could not send. */
internal sealed interface RoomListener

/** A send queue no receiver listens to. */
internal object NoRoomListener : RoomListener

internal fun RoomListener.roomMade() =
    when (this) {
        is StreamInbox -> roomMade()
        is NoRoomListener -> Unit
    }

/** What a receive direction listens to for send room: its stream's send queue, or nothing on a receive-only stream. */
internal sealed interface RoomSource

/** A receive-only stream: no send queue makes room. */
internal object NoRoomSource : RoomSource

internal fun RoomSource.listen(room: RoomListener) =
    when (this) {
        is StreamOutbox -> listen(room)
        is NoRoomSource -> Unit
    }

internal const val OUTBOX_IDLE = 0
internal const val OUTBOX_SCHEDULED = 1

/** The bytes one stream queues at most: past them a send is told [StreamSend.NotSent.WindowFull]. */
internal const val OUTBOX_CAPACITY = 65_536

/** The room a waiting sender is woken for. */
internal const val OUTBOX_LOW_WATER = OUTBOX_CAPACITY / 4
