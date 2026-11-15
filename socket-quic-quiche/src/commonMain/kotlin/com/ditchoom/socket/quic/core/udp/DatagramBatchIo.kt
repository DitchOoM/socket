package com.ditchoom.socket.quic.core.udp

/**
 * One socket, owned by one loop. Never blocks.
 *
 * A pass hands the network one [SendBatch] and takes one [ReceiveBatch] per wake. The core does not know which
 * implementation is in use; a socket's [capability] only ever steps down.
 */
internal interface DatagramBatchIo {
    val capability: BatchCapability

    fun send(batch: SendBatch): SendBatchResult

    fun receive(into: ReceiveBatch): ReceiveBatchResult

    fun close(): SocketClosed
}

internal sealed interface BatchCapability {
    object OneByOne : BatchCapability

    /** `sendmmsg` / `recvmmsg`, `nw_connection_batch`. */
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
    object Off : Gso

    class On(
        val maxSegments: Int,
    ) : Gso
}

internal sealed interface Gro {
    object Off : Gro

    object On : Gro
}

internal sealed interface SendBatchResult {
    object AllTaken : SendBatchResult

    /** The network took [taken] datagrams and refused the next for [why]; the rest stay in the batch. */
    class Partial(
        val taken: Int,
        val why: UdpError,
    ) : SendBatchResult
}

internal sealed interface ReceiveBatchResult {
    class Got(
        val datagrams: Int,
    ) : ReceiveBatchResult

    /** Nothing waiting, or no buffer free to receive into. */
    object Nothing : ReceiveBatchResult

    class Failed(
        val error: UdpError,
    ) : ReceiveBatchResult
}

internal object SocketClosed

/** Every platform error, mapped once, here. */
internal sealed interface UdpError {
    object WouldBlock : UdpError

    object MessageTooLarge : UdpError

    object NetworkUnreachable : UdpError

    object HostUnreachable : UdpError

    object PortUnreachable : UdpError

    object NoBufferSpace : UdpError

    object PermissionDenied : UdpError

    object SocketClosed : UdpError

    /** An error this mapping does not name yet, by its platform class or errno name. */
    class Other(
        val kind: String,
    ) : UdpError
}
