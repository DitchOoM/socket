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
     *
     * [recvInfoOverride] lets the *server* supply a per-datagram recv_info whose `from` is the actual datagram
     * source — required for passive (peer) migration, where one server socket sees a client's source address
     * change. The caller owns the recv_info's lifetime (the server caches them per source). When set it takes
     * precedence over [pathKey]; clients leave it null and use [pathKey] path routing instead.
     *
     * [onRecvInfoConsumed] is invoked exactly once after the driver is done with [recvInfoOverride] — both on
     * the normal path (after `connRecv`) and when this command is dropped without processing (failCommand). The
     * server uses it to track in-flight references so it can safely evict/free a cached recv_info only once no
     * queued packet can still dereference it (the driver's command channel is UNLIMITED, so a lagging driver may
     * hold a recv_info long after the source went idle). Null for the client/path-routed cases the server doesn't own.
     */
    class RecvPacket(
        val buf: PlatformBuffer,
        val len: Int,
        val pathKey: PathKey? = null,
        val recvInfoOverride: QuicheRecvInfo? = null,
        val onRecvInfoConsumed: (() -> Unit)? = null,
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
