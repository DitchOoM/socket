package com.ditchoom.socket.quic

import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * Minimal [QuicheApi] stub for driver unit tests. All native calls are no-ops.
 * Controllable via [established], [closed], and [streamRecvResult].
 */
internal class StubQuicheApi : QuicheApi {
    @Volatile var established = true

    @Volatile var closed = false

    /** If non-null, [connPeerError] returns it — simulates the peer's typed CONNECTION_CLOSE reason. */
    @Volatile var peerError: QuicError? = null

    /** If non-null, [connLocalError] returns it — simulates our local typed CONNECTION_CLOSE reason. */
    @Volatile var localError: QuicError? = null

    @Volatile var streamRecvResult: StreamRecvResult = StreamRecvResult.Done

    /**
     * If non-null, the next [connSend] returns this value (then resets to null).
     * Lets tests force a single [QuicheDriver.flushOutgoing] iteration without
     * a real congestion-control state machine.
     */
    @Volatile var connSendOnce: Int? = null

    /**
     * When true, the FIRST [connSend] observed *after [connClose] has been called* returns one
     * datagram (1300B), then stops. This deterministically forces a single
     * [QuicheDriver.flushOutgoing] -> [UdpChannel.send] during the *close* command's afterCommand —
     * and only then, since every pre-close send happens before [connClose]. Tests gate that send to
     * pin the driver between completing the Close deferred and running updateState(), with no
     * dependence on connSendOnce timing or scheduler luck. See
     * ReactiveDriverTests.close_completes_only_after_state_is_synced.
     */
    @Volatile var emitOneDatagramOnClose = false

    @Volatile private var closeInitiated = false

    @Volatile private var closeDatagramEmitted = false

    // --- Config (all no-ops) ---
    override fun configNew(version: Int) = QuicheConfig(1L)

    override fun configFree(config: QuicheConfig) {}

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ) = 0

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) {}

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {}

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {}

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configSetActiveConnectionIdLimit(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) {}

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) {}

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configEnableEarlyData(config: QuicheConfig) {}

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    // --- Connection ---
    override fun connect(
        serverNameAddr: Long,
        serverNameLen: Int,
        scidAddr: Long,
        scidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ) = QuicheConn(1L)

    override fun connFree(conn: QuicheConn) {}

    /**
     * Sim/trace hook invoked on every [connRecv] with the packet length — lets the W2 simulation
     * harness stamp inbound packets the driver fed to quiche into its [SimTrace] with a virtual
     * timestamp. Additive: null (the default) keeps the stub byte-identical for existing tests.
     */
    @Volatile var onConnRecv: ((Int) -> Unit)? = null

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        onConnRecv?.invoke(bufLen)
        return 0
    }

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int {
        if (emitOneDatagramOnClose && closeInitiated && !closeDatagramEmitted) {
            closeDatagramEmitted = true
            return 1300
        }
        val once = connSendOnce
        if (once != null) {
            connSendOnce = null
            return once
        }
        return 0
    }

    /**
     * If non-empty, each [connStreamRecv] pops the next result (modelling quiche's sequence, e.g. a
     * data-chunk-with-FIN followed by Done). Falls back to [streamRecvResult] once drained.
     */
    val streamRecvSequence: ArrayDeque<StreamRecvResult> = ArrayDeque()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ) = streamRecvSequence.removeFirstOrNull() ?: streamRecvResult

    /** When set, [connStreamSend] returns this instead of [bufLen] — e.g. -1 (QUICHE_ERR_DONE) or a real error. */
    @Volatile var connStreamSendResult: Int? = null

    /** Peer application error code [connStreamSend] reports alongside a STREAM_STOPPED/RESET result. */
    @Volatile var connStreamSendErrorCode: Long? = null

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult {
        val result = connStreamSendResult ?: bufLen
        // Mirror real quiche: out_error_code is written ONLY on STREAM_STOPPED / STREAM_RESET, and is
        // always present there (0 if the peer used 0). So default the code to 0 on those results when a
        // test didn't set one, and leave it null otherwise (a normal send carries no error code).
        val code =
            if (result == QuicheDriver.QUICHE_ERR_STREAM_STOPPED || result == QuicheDriver.QUICHE_ERR_STREAM_RESET) {
                connStreamSendErrorCode ?: 0L
            } else {
                connStreamSendErrorCode
            }
        return StreamSendResult(result, code)
    }

    /** Records each [connStreamShutdown] call as (streamId, direction, errorCode) so tests can assert resets. */
    val streamShutdowns = mutableListOf<Triple<Long, Int, Long>>()

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int {
        streamShutdowns += Triple(streamId.id, direction, err)
        return 0
    }

    /** Length [connPeerCert] reports (0 = "no peer certificate"). The stub has no real native [buf] to
     *  copy into, so it never writes — driver-plumbing tests only assert on the returned length. */
    @Volatile var peerCertLen: Int = 0

    override fun connPeerCert(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ) = peerCertLen

    override fun connPeerError(conn: QuicheConn): QuicError? = peerError

    override fun connLocalError(conn: QuicheConn): QuicError? = localError

    // --- Unreliable datagrams (RFC 9221) ---

    /** Records the last [configEnableDgram] call so tests can assert it was wired. */
    @Volatile var dgramEnabled: Boolean = false

    override fun configEnableDgram(
        config: QuicheConfig,
        enabled: Boolean,
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) {
        dgramEnabled = enabled
    }

    /** When set, [connDgramSend] returns this instead of [bufLen] — e.g. -1 (QUICHE_ERR_DONE) or a real error. */
    @Volatile var connDgramSendResult: Int? = null

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ) = connDgramSendResult ?: bufLen

    /** Drained first by [connDgramRecv]; falls back to [dgramRecvResult] when empty. */
    val dgramRecvSequence: ArrayDeque<StreamRecvResult> = ArrayDeque()

    @Volatile var dgramRecvResult: StreamRecvResult = StreamRecvResult.Done

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ) = dgramRecvSequence.removeFirstOrNull() ?: dgramRecvResult

    @Volatile var hasReadableDgram: Boolean = false

    override fun hasReadableDgram(conn: QuicheConn) = hasReadableDgram

    @Volatile var dgramMaxWritableLen: MaxDatagramSize = MaxDatagramSize.Unavailable

    override fun connDgramMaxWritableLen(conn: QuicheConn) = dgramMaxWritableLen

    override fun connIsEstablished(conn: QuicheConn) = established

    override fun connIsClosed(conn: QuicheConn) = closed

    /** When true, [connIsTimedOut] reports a timeout — drives the IdleTimeout close-reason fallback. */
    @Volatile var timedOut = false

    override fun connIsTimedOut(conn: QuicheConn) = timedOut

    /** Controllable quiche timeout. Null (default) = "no quiche timer pending", so the keepalive deadline
     *  is the only thing that can wake the driver loop — see the keepalive driver tests. */
    @Volatile var connTimeout: Duration? = null

    override fun connTimeout(conn: QuicheConn): Duration? = connTimeout

    /** Counts timer fires the driver handed to quiche (i.e. NOT turned into a keepalive PING). */
    var onTimeoutCount = 0
        private set

    /** When set, a handed-to-quiche timer fire idle-closes the connection (mirrors quiche's idle timeout). */
    @Volatile var closeOnTimeout = false

    override fun connOnTimeout(conn: QuicheConn) {
        onTimeoutCount++
        if (closeOnTimeout) closed = true
    }

    /** Counts reactive-keepalive PINGs the driver scheduled, so tests can assert on them. */
    var ackElicitingCount = 0
        private set

    /**
     * Sim/trace hook invoked on every [connSendAckEliciting] — lets the W2 simulation harness stamp
     * keepalive PINGs into its [SimTrace] with a virtual timestamp. Additive: null (the default)
     * keeps the stub byte-identical for existing tests.
     */
    @Volatile var onAckEliciting: (() -> Unit)? = null

    override fun connSendAckEliciting(conn: QuicheConn): Int {
        ackElicitingCount++
        onAckEliciting?.invoke()
        return 0
    }

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int {
        closeInitiated = true
        return 0
    }

    // --- Server (no-ops) ---
    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

    override fun configLoadVerifyLocationsFromFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

    override fun headerInfo(
        buf: Long,
        bufLen: Int,
        dcil: Int,
        versionOut: Long,
        typeOut: Long,
        scidOut: Long,
        scidLenOut: Long,
        dcidOut: Long,
        dcidLenOut: Long,
        tokenOut: Long,
        tokenLenOut: Long,
    ) = 0

    override fun accept(
        scidAddr: Long,
        scidLen: Int,
        odcidAddr: Long,
        odcidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ) = QuicheConn(1L)

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ) = 0

    // --- Stream iteration ---
    override fun connReadable(conn: QuicheConn) = QuicheStreamIter(0L)

    /**
     * Stream IDs to report as writable on the next [connWritable] poll, drained by [streamIterNext].
     * The driver calls `connWritable` once per `afterCommand` and drains the iterator, so a test arms
     * this right before triggering one command to fire exactly one `writableSignal` wakeup. Non-empty
     * ⇒ [connWritable] returns a live iterator handle (1L). (The stub's `connReadable` stays exhausted,
     * so `streamIterNext` is only ever exercised by the writable path.)
     */
    val writableStreams: ArrayDeque<Long> = ArrayDeque()

    override fun connWritable(conn: QuicheConn) = if (writableStreams.isEmpty()) QuicheStreamIter(0L) else QuicheStreamIter(1L)

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? = writableStreams.removeFirstOrNull()?.let { QuicStreamId(it) }

    override fun streamIterFree(iter: QuicheStreamIter) {}

    // --- Helpers ---
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ) = QuicheRecvInfo(1L)

    override fun recvInfoFree(info: QuicheRecvInfo) {}

    override fun sendInfoNew() = QuicheSendInfo(1L)

    override fun sendInfoFree(info: QuicheSendInfo) {}

    override fun sendInfoToAddr(info: QuicheSendInfo) = 0L

    override fun sendInfoToAddrLen(info: QuicheSendInfo) = 0

    override fun sendInfoFromAddr(info: QuicheSendInfo) = 0L

    override fun sendInfoFromAddrLen(info: QuicheSendInfo) = 0

    override fun sockAddrFamily(addr: Long) = 0

    override fun sockAddrPort(addr: Long) = 0

    override fun sockAddrV4(addr: Long) = 0L

    override fun sockAddrV6Hi(addr: Long) = 0L

    override fun sockAddrV6Lo(addr: Long) = 0L

    // --- Path migration (no-ops) ---
    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ) = 0

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ) = 0

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ) = 0

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ) = 0

    override fun connAvailableDcids(conn: QuicheConn) = 0L

    override fun connScidsLeft(conn: QuicheConn) = 0L

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? = null
}
