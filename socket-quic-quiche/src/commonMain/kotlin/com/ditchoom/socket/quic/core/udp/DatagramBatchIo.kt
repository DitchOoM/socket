package com.ditchoom.socket.quic.core.udp

/**
 * One socket, owned by one loop. Never blocks.
 *
 * A pass hands the network one [SendBatch] and takes one [ReceiveBatch] per wake. The core does not know which
 * implementation is in use; a socket's [capability] only ever steps down. The io never sees a platform buffer:
 * batches hold [NativeBytes].
 */
internal interface DatagramBatchIo {
    val capability: BatchCapability

    fun send(batch: SendBatch): SendBatchResult

    fun receive(into: ReceiveBatch): ReceiveBatchResult

    /** Asked after an unnamed error: does the socket still work? */
    fun probe(): SocketProbe

    /**
     * Loop thread, with the socket out of the poller: closes this socket and opens one bound to the same local address
     * and port, connected to the same peer, on whatever network carries that address now. The 4-tuple is unchanged,
     * so the peer sees the same path. A send racing the swap is answered [UdpError.Replaced].
     */
    fun reopen(): Reopened

    fun close(): SocketClosed
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
