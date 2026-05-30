package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CompletableDeferred

/**
 * Commands processed sequentially by the [QuicheDriver] coroutine.
 *
 * quiche is single-threaded — the driver is the only coroutine that touches it.
 * All parameters use value classes or sealed types — no raw Long mixing possible.
 */
sealed interface QuicheCmd {
    /**
     * Feed an incoming UDP packet to quiche. [buf] ownership transfers to the driver (freed after processing).
     * [pathKey] identifies which local path socket received it (null = the connection's primary path), so the
     * driver hands quiche the matching recv_info during connection migration (slice 3).
     */
    class RecvPacket(
        val buf: PlatformBuffer,
        val len: Int,
        val pathKey: PathKey? = null,
    ) : QuicheCmd

    /** Allocate the next stream ID and create a [StreamSlot]. */
    class OpenStream(
        val result: CompletableDeferred<StreamSlot>,
    ) : QuicheCmd

    /** Read data from a QUIC stream. */
    class StreamRecv(
        val streamId: Long,
        val addr: Long,
        val bufLen: Int,
        val result: CompletableDeferred<StreamRecvResult>,
    ) : QuicheCmd

    /** Write data to a QUIC stream. */
    class StreamSend(
        val streamId: Long,
        val addr: Long,
        val bufLen: Int,
        val fin: Boolean,
        val result: CompletableDeferred<Int>,
    ) : QuicheCmd

    /** Gracefully close the connection. */
    class Close(
        val error: QuicError,
        val result: CompletableDeferred<Unit>,
    ) : QuicheCmd

    /**
     * Actively migrate the connection to a new local path bound to [localHost]:[localPort]
     * (null host = default interface, 0 port = ephemeral). The driver opens the path socket,
     * probes it, and on validation switches the active path (slice 3).
     */
    class Migrate(
        val localHost: String?,
        val localPort: Int,
        val result: CompletableDeferred<MigrationResult>,
    ) : QuicheCmd
}
