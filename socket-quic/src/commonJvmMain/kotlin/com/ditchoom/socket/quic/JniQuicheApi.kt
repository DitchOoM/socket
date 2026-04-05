package com.ditchoom.socket.quic

import dalvik.annotation.optimization.FastNative

/**
 * JNI-based quiche bindings for JDK < 21.
 *
 * Value classes are unwrapped at the JNI boundary — the `external` functions
 * use raw [Long] since JNI doesn't understand Kotlin inline classes.
 * The public API uses [QuicheConfig], [QuicheConn], etc. for type safety.
 *
 * On JDK 21+, shadowed by FFM implementation via multi-release JAR.
 */
object JniQuicheApi : QuicheApi {
    init {
        NativeLibLoader.load("quiche_jni")
    }

    // --- Config ---
    override fun configNew(version: Int): QuicheConfig = QuicheConfig(nConfigNew(version))

    override fun configFree(config: QuicheConfig) = nConfigFree(config.ptr)

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = nConfigSetApplicationProtos(config.ptr, protosAddr, protosLen)

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) = nConfigSetMaxIdleTimeout(config.ptr, timeout)

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) = nConfigSetMaxRecvUdpPayloadSize(config.ptr, size)

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) = nConfigSetMaxSendUdpPayloadSize(config.ptr, size)

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxData(config.ptr, v)

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamDataBidiLocal(config.ptr, v)

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamDataBidiRemote(config.ptr, v)

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamDataUni(config.ptr, v)

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamsBidi(config.ptr, v)

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamsUni(config.ptr, v)

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigSetDisableActiveMigration(config.ptr, v)

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigVerifyPeer(config.ptr, v)

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigEnablePacing(config.ptr, v)

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetMaxPacingRate(config.ptr, v)

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) = nConfigSetCcAlgorithm(config.ptr, algo)

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigEnableHystart(config.ptr, v)

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) = nConfigSetInitialCongestionWindowPackets(config.ptr, packets)

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetMaxConnectionWindow(config.ptr, v)

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetMaxStreamWindow(config.ptr, v)

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigDiscoverPmtu(config.ptr, v)

    override fun configEnableEarlyData(config: QuicheConfig) = nConfigEnableEarlyData(config.ptr)

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigGrease(config.ptr, v)

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
    ): QuicheConn =
        QuicheConn(
            nConnect(
                serverNameAddr,
                serverNameLen,
                scidAddr,
                scidLen,
                localAddr,
                localAddrLen,
                peerAddr,
                peerAddrLen,
                config.ptr,
            ),
        )

    override fun connFree(conn: QuicheConn) = nConnFree(conn.ptr)

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = nConnRecv(conn.ptr, buf, bufLen, recvInfo.ptr)

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = nConnSend(conn.ptr, buf, bufLen, sendInfo.ptr)

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: Long,
        buf: Long,
        bufLen: Int,
    ): Long = nConnStreamRecv(conn.ptr, streamId, buf, bufLen)

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: Long,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int = nConnStreamSend(conn.ptr, streamId, buf, bufLen, fin)

    override fun connIsEstablished(conn: QuicheConn): Boolean = nConnIsEstablished(conn.ptr)

    override fun connIsClosed(conn: QuicheConn): Boolean = nConnIsClosed(conn.ptr)

    override fun connIsTimedOut(conn: QuicheConn): Boolean = nConnIsTimedOut(conn.ptr)

    override fun connTimeoutAsNanos(conn: QuicheConn): Long = nConnTimeoutAsNanos(conn.ptr)

    override fun connOnTimeout(conn: QuicheConn) = nConnOnTimeout(conn.ptr)

    override fun connClose(
        conn: QuicheConn,
        app: Boolean,
        err: Long,
        reasonAddr: Long,
        reasonLen: Int,
    ): Int = nConnClose(conn.ptr, app, err, reasonAddr, reasonLen)

    // --- Helpers ---
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo = QuicheRecvInfo(nRecvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen))

    override fun recvInfoFree(info: QuicheRecvInfo) = nRecvInfoFree(info.ptr)

    override fun sendInfoNew(): QuicheSendInfo = QuicheSendInfo(nSendInfoNew())

    override fun sendInfoFree(info: QuicheSendInfo) = nSendInfoFree(info.ptr)

    override fun sendInfoToAddr(info: QuicheSendInfo): Long = nSendInfoToAddr(info.ptr)

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int = nSendInfoToAddrLen(info.ptr)

    // --- JNI externals (raw Long — JNI doesn't understand inline classes) ---
    @JvmStatic private external fun nConfigNew(version: Int): Long

    @JvmStatic private external fun nConfigFree(config: Long)

    @JvmStatic private external fun nConfigSetApplicationProtos(
        config: Long,
        protosAddr: Long,
        protosLen: Int,
    ): Int

    @JvmStatic private external fun nConfigSetMaxIdleTimeout(
        config: Long,
        timeout: Long,
    )

    @JvmStatic private external fun nConfigSetMaxRecvUdpPayloadSize(
        config: Long,
        size: Long,
    )

    @JvmStatic private external fun nConfigSetMaxSendUdpPayloadSize(
        config: Long,
        size: Long,
    )

    @JvmStatic private external fun nConfigSetInitialMaxData(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetInitialMaxStreamDataBidiLocal(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetInitialMaxStreamDataBidiRemote(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetInitialMaxStreamDataUni(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetInitialMaxStreamsBidi(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetInitialMaxStreamsUni(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetDisableActiveMigration(
        config: Long,
        v: Boolean,
    )

    @JvmStatic private external fun nConfigVerifyPeer(
        config: Long,
        v: Boolean,
    )

    @JvmStatic private external fun nConfigEnablePacing(
        config: Long,
        v: Boolean,
    )

    @JvmStatic private external fun nConfigSetMaxPacingRate(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetCcAlgorithm(
        config: Long,
        algo: Int,
    )

    @JvmStatic private external fun nConfigEnableHystart(
        config: Long,
        v: Boolean,
    )

    @JvmStatic private external fun nConfigSetInitialCongestionWindowPackets(
        config: Long,
        packets: Long,
    )

    @JvmStatic private external fun nConfigSetMaxConnectionWindow(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigSetMaxStreamWindow(
        config: Long,
        v: Long,
    )

    @JvmStatic private external fun nConfigDiscoverPmtu(
        config: Long,
        v: Boolean,
    )

    @JvmStatic private external fun nConfigEnableEarlyData(config: Long)

    @JvmStatic private external fun nConfigGrease(
        config: Long,
        v: Boolean,
    )

    @JvmStatic private external fun nConnect(
        serverNameAddr: Long,
        serverNameLen: Int,
        scidAddr: Long,
        scidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: Long,
    ): Long

    @JvmStatic private external fun nConnFree(conn: Long)

    // Hot path — @FastNative for ~3x faster JNI transitions on Android 8+
    // (no-op on JVM, annotation resolved by ART at runtime)
    @FastNative
    @JvmStatic
    private external fun nConnRecv(
        conn: Long,
        buf: Long,
        bufLen: Int,
        recvInfo: Long,
    ): Int

    @FastNative
    @JvmStatic
    private external fun nConnSend(
        conn: Long,
        buf: Long,
        bufLen: Int,
        sendInfo: Long,
    ): Int

    @FastNative
    @JvmStatic
    private external fun nConnStreamRecv(
        conn: Long,
        streamId: Long,
        buf: Long,
        bufLen: Int,
    ): Long

    @FastNative
    @JvmStatic
    private external fun nConnStreamSend(
        conn: Long,
        streamId: Long,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int

    @FastNative
    @JvmStatic
    private external fun nConnIsEstablished(conn: Long): Boolean

    @JvmStatic private external fun nConnIsClosed(conn: Long): Boolean

    @JvmStatic private external fun nConnIsTimedOut(conn: Long): Boolean

    @JvmStatic private external fun nConnTimeoutAsNanos(conn: Long): Long

    @JvmStatic private external fun nConnOnTimeout(conn: Long)

    @JvmStatic private external fun nConnClose(
        conn: Long,
        app: Boolean,
        err: Long,
        reasonAddr: Long,
        reasonLen: Int,
    ): Int

    @JvmStatic private external fun nRecvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): Long

    @JvmStatic private external fun nRecvInfoFree(ptr: Long)

    @JvmStatic private external fun nSendInfoNew(): Long

    @JvmStatic private external fun nSendInfoFree(ptr: Long)

    @JvmStatic private external fun nSendInfoToAddr(ptr: Long): Long

    @JvmStatic private external fun nSendInfoToAddrLen(ptr: Long): Int
}
