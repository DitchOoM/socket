package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CompletableDeferred

/**
 * Where a datagram entered the driver — and therefore which recv_info tells quiche the truth about
 * where it arrived.
 *
 * Exhaustive over the two ways a datagram can enter: a client connection owns one socket per path
 * (its reader tags each packet with that path's key), and a server owns one shared socket for all
 * connections (its receive loop resolves the source address itself). There is no third ingress.
 *
 * Replaces three co-nullable `RecvPacket` fields (`pathKey?`, `recvInfoOverride?`,
 * `onRecvInfoConsumed?`) whose legal combinations were exactly these shapes plus an "untagged"
 * default that no caller used; everything else expressible — a path-tagged packet carrying a server
 * override, a consumed-callback with nothing to consume, a packet from nowhere — was representable
 * and impossible.
 */
sealed interface PacketSource {
    /** A client path reader's packet: it arrived on the socket bound to [key]. */
    class FromPath(
        val key: PathKey,
    ) : PacketSource

    /**
     * The *server's* shared socket: [recvInfo]'s `from` is the datagram's real source — required for
     * passive (peer) migration, where one server socket sees a client's source address change. The
     * server owns the recv_info's lifetime (cached per source); [onConsumed] is invoked exactly once
     * when the driver is done with it — after `connRecv`, or when the command is dropped without
     * processing (failCommand) — so the server can evict/free a cached recv_info only once no queued
     * packet can still dereference it (the command channel is UNLIMITED, so a lagging driver may hold
     * one long after the source went idle).
     */
    class FromServerSocket(
        val recvInfo: QuicheRecvInfo,
        val onConsumed: () -> Unit,
    ) : PacketSource
}

/**
 * Commands processed sequentially by the [QuicheDriver] coroutine.
 *
 * quiche is single-threaded — the driver is the only coroutine that touches it.
 * All parameters use value classes or sealed types — no raw Long mixing possible.
 */
sealed interface QuicheCmd {
    /**
     * Feed an incoming UDP packet to quiche. [buf] ownership transfers to the driver (freed after
     * processing). [source] says where the datagram entered — and therefore which recv_info tells
     * quiche the truth about it. See [PacketSource].
     */
    class RecvPacket(
        val buf: PlatformBuffer,
        val len: Int,
        val source: PacketSource,
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

    /**
     * Read data from a QUIC stream into [buf]. quiche WRITES into that memory on the driver loop
     * long after the reader that enqueued this command may have stopped naming its buffer, which is
     * why [buf] carries the owner and not a bare address — see [QuicheMemory].
     */
    class StreamRecv(
        val streamId: Long,
        val buf: QuicheMemory,
        val bufLen: Int,
        val result: CompletableDeferred<StreamRecvResult>,
    ) : QuicheCmd

    /**
     * Write data to a QUIC stream from [buf]. [QuicheMemory.Empty] is the FIN-only send
     * (`stream_send(len = 0, fin = true)`), which carries no payload at all.
     */
    class StreamSend(
        val streamId: Long,
        val buf: QuicheMemory,
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
     * Send one unreliable datagram (RFC 9221) from [buf]. [result] is the quiche
     * return: bytes written (== [bufLen]) on success, or a negative code ([QuicheDriver.QUICHE_ERR_DONE]
     * when the send queue is full — backpressure). The caller owns the buffer; the driver only reads
     * it, and [buf] is what keeps that memory mapped until it has (see [QuicheMemory]).
     * [QuicheMemory.Empty] is the zero-length datagram, which RFC 9221 allows.
     */
    class DgramSend(
        val buf: QuicheMemory,
        val bufLen: Int,
        val result: CompletableDeferred<Int>,
    ) : QuicheCmd

    /**
     * Receive one unreliable datagram into [buf]. Decoded into [StreamRecvResult]
     * (always `fin = false`): [StreamRecvResult.Data] with the datagram length, [StreamRecvResult.Done]
     * when none is queued, or [StreamRecvResult.Error]. The driver writes into that memory, and [buf]
     * is what keeps it mapped until it has (see [QuicheMemory]).
     */
    class DgramRecv(
        val buf: QuicheMemory,
        val bufLen: Int,
        val result: CompletableDeferred<StreamRecvResult>,
    ) : QuicheCmd

    /**
     * Read the peer's TLS leaf certificate DER into [buf] (`quiche_conn_peer_cert`), for
     * `serverCertificateHashes` leaf-hash pinning. [result] is the DER length: copied into the buffer
     * when it fits ([result] <= [bufLen]); when larger, nothing is copied and the caller re-allocates
     * to [result] bytes and re-issues. 0 = peer presented no certificate. Routed through the driver so
     * the read is serialized with all other quiche-conn access. [buf] carries the owner for the same
     * reason StreamRecv/DgramRecv do (see [QuicheMemory]).
     */
    class PeerCert(
        val buf: QuicheMemory,
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

    /**
     * Read the peer's negotiated transport parameters (`quiche_conn_peer_transport_params`) on the
     * driver loop — the only place quiche may be touched. Completes with
     * [PeerTransportParams.NotYetNegotiated] if the connection is already torn down (failCommand),
     * which is also the honest answer there: nothing negotiated can be read from a freed handle.
     *
     * Routed through the channel rather than read directly for the same reason [Stats] is: a read that
     * raced `connSend`/`connRecv` would be concurrent access to a `quiche_conn`, which is UB.
     */
    class PeerTransportParamsRead(
        val result: CompletableDeferred<PeerTransportParams>,
    ) : QuicheCmd

    /**
     * Read the source connection IDs quiche currently considers active.
     *
     * Routed through the channel for the same reason [PeerTransportParamsRead] is: a read racing
     * `connSend`/`connRecv` would be concurrent access to a `quiche_conn`, which is UB. That
     * confinement is also what makes the count-then-read pair exact — nothing can change the set
     * between [QuicheApi.connActiveScids] and [QuicheApi.connReadSourceIds] on this coroutine.
     */
    class SourceIdsRead(
        val result: CompletableDeferred<List<ByteArray>>,
    ) : QuicheCmd

    /**
     * Close the connection with [error] — a CONNECTION_CLOSE carrying its code, or, for an error that
     * has no transport code, NO_ERROR on the wire and the error itself recorded as the driver's own
     * verdict so the connection still reports it (see [wireCloseError]).
     */
    class Close(
        val error: QuicError,
        val result: CompletableDeferred<Unit>,
    ) : QuicheCmd

    /**
     * Actively migrate the connection to a new local path at [target]. The driver opens the path
     * socket, probes it, and on validation switches the active path (RFC 9000 §9).
     */
    class Migrate(
        val target: MigrationTarget,
        val result: CompletableDeferred<MigrationResult>,
    ) : QuicheCmd
}

/**
 * What a local close with this error puts on the wire.
 *
 * An error with a transport code is sent as itself. One without ([QuicError.code] `< 0` —
 * [QuicError.HandshakeTimeout], [QuicError.IdleTimeout], [QuicError.PlatformError]) is a *local* fact
 * with no CONNECTION_CLOSE code that means it; RFC 9000 §20.1's NO_ERROR ("closed abruptly in the absence
 * of any error") is the one truthful thing to send, and it is what Chromium sends for its own handshake
 * timeout. Before this, the `-1` went straight into `quiche_conn_close`'s `uint64_t err` and onto the wire
 * as `u64::MAX`. The driver keeps the original as its `LocalCloseVerdict` so the connection still reports it.
 */
internal fun QuicError.wireCloseError(): QuicError = if (code < 0) QuicError.NoError else this
