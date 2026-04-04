package com.ditchoom.socket.quic

/**
 * Platform-agnostic interface for calling quiche's C API from the JVM.
 *
 * All data passes as native addresses — no byte array copies anywhere.
 * Opaque quiche handles use value classes ([QuicheConfig], [QuicheConn], etc.)
 * for compile-time type safety at zero runtime cost.
 *
 * Buffer allocation/pooling is the caller's responsibility via [com.ditchoom.buffer.BufferFactory].
 *
 * Two implementations:
 * - [jvm21Main] `FfmQuicheApi`: FFM downcalls — zero-copy, no JNI.
 * - [jvmMain] `JniQuicheApi`: JNI `external` — zero-copy via direct buffer addresses.
 *
 * The multi-release JAR selects the FFM version on JDK 21+ automatically.
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

    fun connStreamRecv(
        conn: QuicheConn,
        streamId: Long,
        buf: Long,
        bufLen: Int,
    ): Long

    fun connStreamSend(
        conn: QuicheConn,
        streamId: Long,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int

    fun connIsEstablished(conn: QuicheConn): Boolean

    fun connIsClosed(conn: QuicheConn): Boolean

    fun connIsTimedOut(conn: QuicheConn): Boolean

    fun connTimeoutAsNanos(conn: QuicheConn): Long

    fun connOnTimeout(conn: QuicheConn)

    fun connClose(
        conn: QuicheConn,
        app: Boolean,
        err: Long,
        reasonAddr: Long,
        reasonLen: Int,
    ): Int

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

    // ALPN encoding moved to AlpnCodec (commonMain) — writes directly into BufferFactory buffers
}

/**
 * Loads the appropriate [QuicheApi] for the current JDK.
 * On JDK 21+, the multi-release JAR shadows this with the FFM version.
 */
fun loadQuicheApi(): QuicheApi = JniQuicheApi
