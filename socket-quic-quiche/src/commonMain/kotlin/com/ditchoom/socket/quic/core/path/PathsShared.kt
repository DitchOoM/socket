package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PATH_VALIDATION_EXPANSION
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SocketTicket
import kotlin.jvm.JvmInline

/** Where one datagram quiche wrote goes: to [target] from the socket [socket] names, or nowhere, as no socket of ours holds its path. */
internal sealed interface Outgoing {
    class To(
        val target: SendTarget,
        val socket: SocketTicket,
    ) : Outgoing

    data object Dropped : Outgoing
}

/** The connected peer, from the socket a connection opened on: where a client's datagrams go until a path says more. */
internal val CONNECTED_PEER = Outgoing.To(SendTarget.ConnectedPeer, SocketTicket.FIRST)

/** How a connection's datagrams are sent out: all the same way, or each by the address quiche wrote it for. */
internal sealed interface Sorting {
    /** Every datagram goes to [to]. */
    class Fixed(
        val to: Outgoing.To,
    ) : Sorting

    /** Each datagram's own send info names the path it is for. */
    data object ByAddress : Sorting
}

/** How many bytes quiche may write into one datagram. */
@JvmInline
internal value class Window(
    val bytes: Int,
) {
    /** While a path is validated, at most the floor RFC 9000 §8.2.1 sets for the datagrams quiche expands on it. */
    fun whileValidating(): Window = Window(minOf(bytes, PATH_VALIDATION_EXPANSION))
}

/** Whether the quiche-ordered sockaddr at [address] names [key], read field by field so nothing is allocated. */
internal fun QuicheConnRef.addressIs(
    address: Long,
    key: PathKey,
): Boolean {
    val api = api
    if (api.sockAddrFamily(address) != key.family || api.sockAddrPort(address) != key.port) return false
    return when (key.family) {
        V4 -> api.sockAddrV4(address) == key.lo
        else -> api.sockAddrV6Hi(address) == key.hi && api.sockAddrV6Lo(address) == key.lo
    }
}

/** One path event quiche reported: what happened and, for the event's addresses, where they are read from. */
internal sealed interface EventNext {
    data object Drained : EventNext

    class Event(
        val type: QuichePathEventType,
    ) : EventNext
}

/** A connection's path-event memory: none until it has a path to follow. */
internal sealed interface EventMemory {
    data object None : EventMemory

    class Held(
        val scratch: EventScratch,
    ) : EventMemory
}

/** What [EventScratch.of] answers. */
internal sealed interface EventsTaken {
    class Taken(
        val scratch: EventScratch,
    ) : EventsTaken

    /** The factory made memory without an address. */
    data object NotNative : EventsTaken
}

/**
 * The native memory quiche writes one path event's addresses into: a connection's own, made the first time it has a
 * path to follow and freed with it.
 */
@Confined
internal class EventScratch private constructor(
    private val bytes: NativeBytes,
) {
    /** Where the last event's local address was written. */
    val local: Long get() = bytes.address + LOCAL

    /** Where the last event's peer address was written. */
    val peer: Long get() = bytes.address + PEER

    /** The next event quiche holds for [q], consumed. */
    fun next(q: QuicheConnRef): EventNext {
        val at = bytes.address
        val type = q.api.connPathEventNext(q.handle, at + LOCAL, at + LOCAL_LENGTH, at + PEER, at + PEER_LENGTH)
        return if (type == null) EventNext.Drained else EventNext.Event(type)
    }

    fun free() = bytes.free()

    companion object {
        fun of(factory: BufferFactory): EventsTaken =
            when (val taken = NativeBytes.of(factory.allocate(BYTES))) {
                is Addressed.Native -> EventsTaken.Taken(EventScratch(taken.bytes))
                is Addressed.NotAddressable -> EventsTaken.NotNative
            }

        private const val SOCKADDR_STORAGE = 128
        private const val LOCAL = 0
        private const val LOCAL_LENGTH = SOCKADDR_STORAGE
        private const val PEER = SOCKADDR_STORAGE + 8
        private const val PEER_LENGTH = 2 * SOCKADDR_STORAGE + 8
        private const val BYTES = 2 * SOCKADDR_STORAGE + 16
    }
}

private const val V4 = 4

/** The endpoint a client left last: kept while a datagram that crossed the move may still come for it. */
internal sealed interface Previous {
    data object None : Previous

    class Held(
        val local: LocalAddr,
    ) : Previous
}
