package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * Platform-agnostic interface for quiche's C API.
 *
 * All data passes as native addresses — no byte array copies anywhere.
 * Opaque quiche handles use value classes ([QuicheConfig], [QuicheConn], etc.)
 * for compile-time type safety at zero runtime cost.
 *
 * Platform implementations decode format-specific results into common Kotlin types:
 * - [StreamRecvResult] replaces packed Long (JNI) or output params (cinterop)
 * - [Duration] replaces raw nanosecond Long with platform-specific "no timeout" sentinel
 * - [QuicStreamId] replaces raw Long stream identifiers
 * - [QuicError] replaces raw app-boolean + error-code pairs
 *
 * Buffer allocation/pooling is the caller's responsibility via [com.ditchoom.buffer.BufferFactory].
 *
 * Implementations:
 * - JVM (JDK < 21): `JniQuicheApi` — JNI external calls
 * - JVM (JDK 21+): `FfmQuicheApi` — FFM downcalls
 * - Linux/Native: `CinteropQuicheApi` — K/N cinterop
 */
interface QuicheApi {
    // --- Config ---

    fun configNew(version: Int): QuicheConfig

    fun configFree(config: QuicheConfig)

    fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int

    fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    )

    fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    )

    fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    )

    fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    )

    fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    )

    fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    )

    fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configEnableEarlyData(config: QuicheConfig)

    fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    )

    // --- Connection ---

    fun connect(
        serverNameAddr: Long,
        serverNameLen: Int,
        scidAddr: Long,
        scidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ): QuicheConn

    fun connFree(conn: QuicheConn)

    fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int

    fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int

    /**
     * Read from a QUIC stream. Returns a [StreamRecvResult] that indicates data, done, or error.
     * Implementations decode the platform-specific result format (packed Long on JNI,
     * output parameters on cinterop) into this common type.
     */
    fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult

    fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int

    fun connIsEstablished(conn: QuicheConn): Boolean

    fun connIsClosed(conn: QuicheConn): Boolean

    fun connIsTimedOut(conn: QuicheConn): Boolean

    /**
     * Returns the timeout duration until the next quiche timer fires, or `null` if no timeout is set.
     * Implementations normalize platform-specific "no timeout" sentinels (UINT64_MAX on native,
     * negative Long on JVM) into `null`.
     */
    fun connTimeout(conn: QuicheConn): Duration?

    fun connOnTimeout(conn: QuicheConn)

    /**
     * Close the connection with the given [error].
     * Implementations decompose [QuicError] into the C API's `app` flag and error code.
     */
    fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int

    // --- Server-side ---

    fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int

    fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int

    fun headerInfo(
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
    ): Int

    fun accept(
        scidAddr: Long,
        scidLen: Int,
        odcidAddr: Long,
        odcidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ): QuicheConn

    fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int

    // --- Stream iteration ---

    /** Get iterator over readable streams. Check [QuicheStreamIter.isExhausted] before iterating. */
    fun connReadable(conn: QuicheConn): QuicheStreamIter

    /** Get iterator over writable streams. Check [QuicheStreamIter.isExhausted] before iterating. */
    fun connWritable(conn: QuicheConn): QuicheStreamIter

    /**
     * Advance stream iterator. Returns the next [QuicStreamId], or `null` when exhausted.
     * Implementations handle the output-parameter pattern internally.
     */
    fun streamIterNext(iter: QuicheStreamIter): QuicStreamId?

    fun streamIterFree(iter: QuicheStreamIter)

    // --- Helpers ---

    fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo

    fun recvInfoFree(info: QuicheRecvInfo)

    fun sendInfoNew(): QuicheSendInfo

    fun sendInfoFree(info: QuicheSendInfo)

    fun sendInfoToAddr(info: QuicheSendInfo): Long

    fun sendInfoToAddrLen(info: QuicheSendInfo): Int
}
