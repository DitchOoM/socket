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

    /**
     * Allocate the next stream ID and create a [StreamSlot]. [unidirectional] selects the
     * uni-stream ID space (RFC 9000 §2.1) for the locally-initiated control / QPACK streams
     * HTTP/3 needs; the default is a bidirectional stream.
     */
    class OpenStream(
        val result: CompletableDeferred<StreamSlot>,
        val unidirectional: Boolean = false,
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
        val result: CompletableDeferred<StreamSendResult>,
    ) : QuicheCmd

    /**
     * Shut down one direction of a stream with an application error code: [direction] 0 = read
     * (sends STOP_SENDING), 1 = write (sends RESET_STREAM). [result] is the quiche return (0 on success).
     */
    class StreamShutdown(
        val streamId: Long,
        val direction: Int,
        val errorCode: Long,
        val result: CompletableDeferred<Int>,
    ) : QuicheCmd

    /**
     * Send one unreliable datagram (RFC 9221) from [addr]..[addr]+[bufLen]. [result] is the quiche
     * return: bytes written (== [bufLen]) on success, or a negative code ([QuicheDriver.QUICHE_ERR_DONE]
     * when the send queue is full — backpressure). The caller owns the buffer; the driver only reads it.
     */
    class DgramSend(
        val addr: Long,
        val bufLen: Int,
        val result: CompletableDeferred<Int>,
    ) : QuicheCmd

    /**
     * Receive one unreliable datagram into [addr]..[addr]+[bufLen]. Decoded into [StreamRecvResult]
     * (always `fin = false`): [StreamRecvResult.Data] with the datagram length, [StreamRecvResult.Done]
     * when none is queued, or [StreamRecvResult.Error]. The driver writes into [addr]; the caller must
     * keep that buffer alive until [result] completes (see the lifetime guard in receiveDatagram).
     */
    class DgramRecv(
        val addr: Long,
        val bufLen: Int,
        val result: CompletableDeferred<StreamRecvResult>,
    ) : QuicheCmd

    /**
     * Read the peer's TLS leaf certificate DER into [addr]..[addr]+[bufLen] (`quiche_conn_peer_cert`),
     * for `serverCertificateHashes` leaf-hash pinning. [result] is the DER length: copied into [addr]
     * when it fits ([result] <= [bufLen]); when larger, nothing is copied and the caller re-allocates
     * to [result] bytes and re-issues. 0 = peer presented no certificate. Routed through the driver so
     * the read is serialized with all other quiche-conn access. The caller owns the buffer at [addr]
     * and must keep it alive until [result] completes (mirrors the StreamRecv/DgramRecv lifetime rule).
     */
    class PeerCert(
        val addr: Long,
        val bufLen: Int,
        val result: CompletableDeferred<Int>,
    ) : QuicheCmd

    /**
     * Read a [QuicStatsSnapshot] (conn-level + active-path quiche stats) on the driver loop —
     * the only place quiche may be touched. [result] carries `null` members on backends that have
     * not bound the stats FFI, and completes with an all-null snapshot if the connection is
     * already torn down (failCommand). Used by [QuicheDriver.stats].
     */
    class Stats(
        val result: CompletableDeferred<QuicStatsSnapshot>,
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
