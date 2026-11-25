package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Backlog
import com.ditchoom.socket.quic.core.time.Nanos

/** The room a received datagram is read into: more than any datagram the endpoint's sockets receive. */
internal const val DATAGRAM_ROOM = 1350

/** Whether a parked receive still wants a datagram. */
internal enum class Interest { Wanted, Withdrawn }

/** Where a receive stands after [DatagramBox.receive]: answered, or parked until a datagram arrives. */
internal enum class Receipt { Delivered, Parked }

/** A receive parked in the box. Runs inside the connection. */
internal interface DatagramWaiter {
    /** Where the next datagram is written. */
    val address: Long

    /** The room at [address]. */
    val capacity: Int

    fun interest(): Interest

    /** A datagram of [length] bytes is at [address]. */
    fun arrived(length: Int)

    /** quiche refused the receive with [code]. */
    fun refused(code: Int)

    /** The receive was withdrawn: its memory is let go. */
    fun discard()

    /** The connection ended with the receive still parked. */
    fun connectionEnded(end: ConnEnd)
}

/** Inside, at [now]: the datagram work of an established connection, served each turn: the room is read, what quiche received moves on, and idle send memory is let go. */
internal fun Datagrams.wake(now: Nanos) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> {
            out.readRoom()
            box.drain(now)
            out.trim(now)
        }
    }

/** Inside: quiche has read a datagram, at [now]. */
internal fun Datagrams.received(now: Nanos) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> box.drain(now)
    }

/** Inside, as a write begins or its output runs dry at [now]: queued sends go to quiche, which may then have more to write. */
internal fun Datagrams.refill(now: Nanos): Refill =
    when (this) {
        is Datagrams.Off -> Refill.Nothing
        is Datagrams.On -> out.feed(now)
    }

/** Inside: quiche wrote a packet, which may have taken datagrams out of its queue. */
internal fun Datagrams.quicheWrote() =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> out.quicheWrote()
    }

/** Whether datagrams wait to be sent. */
internal fun Datagrams.backlog(): Backlog =
    when (this) {
        is Datagrams.Off -> Backlog.None
        is Datagrams.On -> out.backlog()
    }

/** Inside: the connection's close point, with [end]: nothing more is sent; none are left to write. */
internal fun Datagrams.closed(end: ConnEnd): Datagrams {
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> out.close(end)
    }
    return Datagrams.Off
}

/** Inside: the connection ended with [end]. */
internal fun Datagrams.endAll(end: ConnEnd) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> {
            out.close(end)
            box.endAll(end)
        }
    }

/** Any thread: the bytes one datagram may carry as of the connection's last turn, or 0. */
internal fun Datagrams.maxBytes(): Int =
    when (this) {
        is Datagrams.Off -> 0
        is Datagrams.On -> out.room().maxBytes()
    }
