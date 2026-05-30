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

    @Volatile var streamRecvResult: StreamRecvResult = StreamRecvResult.Done

    /**
     * If non-null, the next [connSend] returns this value (then resets to null).
     * Lets tests force a single [QuicheDriver.flushOutgoing] iteration without
     * a real congestion-control state machine.
     */
    @Volatile var connSendOnce: Int? = null

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

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ) = 0

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int {
        val once = connSendOnce
        if (once != null) {
            connSendOnce = null
            return once
        }
        return 0
    }

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ) = streamRecvResult

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ) = bufLen

    override fun connIsEstablished(conn: QuicheConn) = established

    override fun connIsClosed(conn: QuicheConn) = closed

    override fun connIsTimedOut(conn: QuicheConn) = false

    override fun connTimeout(conn: QuicheConn): Duration? = null

    override fun connOnTimeout(conn: QuicheConn) {}

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ) = 0

    // --- Server (no-ops) ---
    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

    override fun configLoadPrivKeyFromPemFile(
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

    override fun connWritable(conn: QuicheConn) = QuicheStreamIter(0L)

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? = null

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
