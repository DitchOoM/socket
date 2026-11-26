package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey

/**
 * A socket owned by one loop, with at most one more beside it while a client moves: the carrying socket, and a second
 * one opened on another address whose path is being probed. Never blocks.
 *
 * A pass hands the network one [SendBatch] and takes one [ReceiveBatch] per wake. The core does not know which
 * implementation is in use; a socket's [capability] only ever steps down. The io never sees a platform buffer:
 * batches hold [NativeBytes].
 */
internal interface DatagramBatchIo {
    val capability: BatchCapability

    /**
     * Sends each pending datagram from the socket [SendBatch.socketOf] names. A datagram for a socket this io does not
     * hold (one closed or never opened) is taken and goes nowhere. What the second socket refuses is dropped, never
     * reported: a probe's socket cannot fault the one carrying the connection.
     */
    fun send(batch: SendBatch): SendBatchResult

    /** Receives what waits on the carrying socket. */
    fun receive(into: ReceiveBatch): ReceiveBatchResult

    /** Receives what waits on the second socket, while one is open; a failure there is read as nothing waiting. */
    fun receiveSecond(into: ReceiveBatch): ReceiveBatchResult = ReceiveBatchResult.Nothing

    /** Asked after an unnamed error: does the socket still work? */
    fun probe(): SocketProbe

    /**
     * Loop thread, with the socket out of the poller: closes the carrying socket and opens one bound to the same local
     * address and port, connected to the same peer, on whatever network carries that address now. The 4-tuple is
     * unchanged, so the peer sees the same path. A send racing the swap is answered [UdpError.Replaced].
     */
    fun reopen(): Reopened

    /**
     * Loop thread, with the second socket out of the poller: [reopen] for the second socket, which holds the address the
     * connection is moving to and so the one a changed network carries now.
     */
    fun reopenSecond(): Reopened = Reopened.SameEndpoint

    /**
     * Loop thread, with no second socket open: opens one beside the carrying socket, bound to [bind]'s address on a port
     * of its own and connected to the same peer, and names it [ticket]. The carrying socket is untouched: the peer sees
     * a new path, and the old one keeps carrying until the loop promotes or closes the second.
     */
    fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside = Beside.Unsupported

    /**
     * Loop thread, each pass while an [openBeside] answered [Beside.Arriving]: whether the second socket is ready.
     * [Beside.Opened] and [Beside.Refused] end the wait; [Beside.Arriving] says to ask again at the next wake.
     */
    fun settleBeside(): Beside = Beside.Unsupported

    /** Loop thread, with the sockets out of the poller: the second socket carries from now on, and the old one is closed. */
    fun promote() = Unit

    /** Loop thread, with the second socket out of the poller: closes the second socket, or the one being made. */
    fun closeSecond() = Unit

    /** Closes every socket this io holds. */
    fun close(): SocketClosed
}

/** What a [DatagramBatchIo.openBeside] did. */
internal sealed interface Beside {
    /** A second socket is bound to [local]; the carrying one is unchanged. */
    class Opened(
        val local: PathKey,
    ) : Beside

    /** No socket could be opened at the address: the carrying one is unchanged. */
    class Refused(
        val thrown: Thrown,
    ) : Beside

    /** A socket is being made without waiting for it: [DatagramBatchIo.settleBeside] says when it is ready, and wakes the loop. */
    data object Arriving : Beside

    /** This kind of socket follows the device's paths by itself: no second one is opened. */
    data object Unsupported : Beside
}

/** What a [DatagramBatchIo.reopen] did. */
internal sealed interface Reopened {
    /** A new socket holds the same local address and port. */
    data object SameEndpoint : Reopened

    /** No socket could be opened there again: the old one is closed. */
    class Refused(
        val thrown: Thrown,
    ) : Reopened
}

internal sealed interface BatchCapability {
    data object OneByOne : BatchCapability

    /** `sendmmsg` / `recvmmsg`. */
    class Multi(
        val maxDatagrams: Int,
    ) : BatchCapability

    /** `UDP_SEGMENT` / `UDP_GRO`. */
    class Segmented(
        val maxDatagrams: Int,
        val send: Gso,
        val receive: Gro,
    ) : BatchCapability
}

internal sealed interface Gso {
    data object Off : Gso

    class On(
        val maxSegments: Int,
    ) : Gso
}

internal enum class Gro { Off, On }

internal sealed interface SendBatchResult {
    data object AllTaken : SendBatchResult

    /** The network took [taken] datagrams and refused the next for [why]; the rest stay in the batch. */
    class Partial(
        val taken: Int,
        val why: UdpError,
    ) : SendBatchResult
}

internal sealed interface ReceiveBatchResult {
    /** The batch holds what was received; [ReceiveBatch.count] says how many. */
    data object Got : ReceiveBatchResult

    /**
     * Like [Got], and whole messages still wait inside the io, where the poller cannot see them: the loop must read
     * again before it sleeps, with no new datagram needed.
     */
    data object GotWithBacklog : ReceiveBatchResult

    /** Nothing waiting, or no buffer free to receive into. */
    data object Nothing : ReceiveBatchResult

    class Failed(
        val error: UdpError,
    ) : ReceiveBatchResult
}

internal data object SocketClosed
