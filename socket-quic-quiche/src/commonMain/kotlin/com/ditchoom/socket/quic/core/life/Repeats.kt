package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SocketTicket

/**
 * Whether the peer's address is validated (RFC 9000 §8.1): until it is, a server sends it at most three times the
 * bytes it received from it, so a lingering close is never repeated unprompted and is answered only within that.
 */
internal enum class PeerAddress { Validated, Unvalidated }

/**
 * One datagram of the close quiche wrote: [length] bytes at [offset] of the write's batch, to [target] from the socket
 * [socket] names. A close before
 * the handshake is confirmed is two, its Handshake packet first and its 1-RTT copy after (RFC 9000 §10.2.3): a peer
 * reads the one it has keys for.
 */
internal class CloseDatagram(
    val offset: Int,
    val length: Int,
    val target: SendTarget,
    val socket: SocketTicket,
)

/** The unprompted repeat of a lingering close that is owed next: one probe timeout after the close, then two, then none. */
private enum class Repeat { First, Second, Done }

/**
 * The unprompted repeats of a lingering close, for a peer whose copy was lost and that has nothing in flight to draw
 * an answer; none toward an unvalidated address. A part of [Wire.Answering], so no repeat exists without a kept close;
 * inside the connection only.
 */
@Confined
internal class Repeats(
    private val sent: Nanos,
    private val ptoNanos: Long,
    private val until: Nanos,
    address: PeerAddress,
) {
    private var next =
        when (address) {
            PeerAddress.Validated -> Repeat.First
            PeerAddress.Unvalidated -> Repeat.Done
        }

    /** At [now]: whether a repeat is owed, one however many a late serve passed, and only before the linger's end. */
    fun owed(now: Nanos): Owed {
        val before = next
        while (next != Repeat.Done && now >= dueOf(next)) {
            next =
                when (next) {
                    Repeat.First -> Repeat.Second
                    Repeat.Second, Repeat.Done -> Repeat.Done
                }
        }
        return if (next != before && now < until) Owed.Yes else Owed.No
    }

    /** The peer still sends: its packets draw the answers, and a repeat would only add to them. */
    fun stop() {
        next = Repeat.Done
    }

    fun offerWake(sink: WakeSink) {
        if (next != Repeat.Done) sink.offer(dueOf(next))
    }

    private fun dueOf(repeat: Repeat): Nanos =
        when (repeat) {
            Repeat.First -> sent + ptoNanos
            Repeat.Second, Repeat.Done -> sent + 2 * ptoNanos
        }

    enum class Owed { Yes, No }
}
