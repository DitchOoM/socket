package com.ditchoom.socket.quic

import dalvik.annotation.optimization.FastNative
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

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

    override fun configFree(config: QuicheConfig) = nConfigFree(config.handle)

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = nConfigSetApplicationProtos(config.handle, protosAddr, protosLen)

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) = nConfigSetMaxIdleTimeout(config.handle, timeout)

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) = nConfigSetMaxRecvUdpPayloadSize(config.handle, size)

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) = nConfigSetMaxSendUdpPayloadSize(config.handle, size)

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxData(config.handle, v)

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamDataBidiLocal(config.handle, v)

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamDataBidiRemote(config.handle, v)

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamDataUni(config.handle, v)

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamsBidi(config.handle, v)

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetInitialMaxStreamsUni(config.handle, v)

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigSetDisableActiveMigration(config.handle, v)

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigVerifyPeer(config.handle, v)

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigEnablePacing(config.handle, v)

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetMaxPacingRate(config.handle, v)

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) = nConfigSetCcAlgorithm(config.handle, algo)

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigEnableHystart(config.handle, v)

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) = nConfigSetInitialCongestionWindowPackets(config.handle, packets)

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetMaxConnectionWindow(config.handle, v)

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) = nConfigSetMaxStreamWindow(config.handle, v)

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigDiscoverPmtu(config.handle, v)

    override fun configEnableEarlyData(config: QuicheConfig) = nConfigEnableEarlyData(config.handle)

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) = nConfigGrease(config.handle, v)

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
                config.handle,
            ),
        )

    override fun connFree(conn: QuicheConn) = nConnFree(conn.handle)

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = nConnRecv(conn.handle, buf, bufLen, recvInfo.handle)

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = nConnSend(conn.handle, buf, bufLen, sendInfo.handle)

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        val raw = nConnStreamRecv(conn.handle, streamId.id, buf, bufLen)
        // JNI packs result: lower 63 bits = bytes read, bit 63 = FIN flag.
        // Negative values are quiche error codes (small negative, so lower 63 bits are huge).
        val lower63 = raw and 0x7FFFFFFFFFFFFFFFL
        if (lower63 <= bufLen.toLong()) {
            val bytesRead = lower63.toInt()
            val fin = raw and (1L shl 63) != 0L
            return StreamRecvResult.Data(bytesRead, fin)
        }
        return if (raw == QUICHE_ERR_DONE) StreamRecvResult.Done else StreamRecvResult.Error(raw.toInt())
    }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int = nConnStreamSend(conn.handle, streamId.id, buf, bufLen, fin)

    override fun connIsEstablished(conn: QuicheConn): Boolean = nConnIsEstablished(conn.handle)

    override fun connIsClosed(conn: QuicheConn): Boolean = nConnIsClosed(conn.handle)

    override fun connIsTimedOut(conn: QuicheConn): Boolean = nConnIsTimedOut(conn.handle)

    override fun connTimeout(conn: QuicheConn): Duration? {
        val nanos = nConnTimeoutAsNanos(conn.handle)
        // JNI casts uint64_t to jlong: UINT64_MAX becomes -1L (or any negative)
        return if (nanos < 0) null else nanos.nanoseconds
    }

    override fun connOnTimeout(conn: QuicheConn) = nConnOnTimeout(conn.handle)

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int = nConnClose(conn.handle, error is QuicError.ApplicationError, error.code, 0L, 0)

    // --- Path migration ---
    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int = nConnProbePath(conn.handle, localAddr, localLen, peerAddr, peerLen, seqOut)

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int = nConnMigrate(conn.handle, localAddr, localLen, peerAddr, peerLen, seqOut)

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int = nConnMigrateSource(conn.handle, localAddr, localLen, seqOut)

    override fun connAvailableDcids(conn: QuicheConn): Long = nConnAvailableDcids(conn.handle)

    override fun connScidsLeft(conn: QuicheConn): Long = nConnScidsLeft(conn.handle)

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        val raw = nConnPathEventNext(conn.handle, localOut, localLenOut, peerOut, peerLenOut)
        return if (raw < 0) null else QuichePathEventType.entries[raw]
    }

    // --- Server-side ---
    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = nConfigLoadCertChainFromPemFile(config.handle, pathAddr)

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = nConfigLoadPrivKeyFromPemFile(config.handle, pathAddr)

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
    ): Int =
        nHeaderInfo(
            buf,
            bufLen,
            dcil,
            versionOut,
            typeOut,
            scidOut,
            scidLenOut,
            dcidOut,
            dcidLenOut,
            tokenOut,
            tokenLenOut,
        )

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
    ): QuicheConn =
        QuicheConn(
            nAccept(
                scidAddr,
                scidLen,
                odcidAddr,
                odcidLen,
                localAddr,
                localAddrLen,
                peerAddr,
                peerAddrLen,
                config.handle,
            ),
        )

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int = nNegotiateVersion(scidAddr, scidLen, dcidAddr, dcidLen, outAddr, outLen)

    // --- Stream iteration ---
    override fun connReadable(conn: QuicheConn): QuicheStreamIter = QuicheStreamIter(nConnReadable(conn.handle))

    override fun connWritable(conn: QuicheConn): QuicheStreamIter = QuicheStreamIter(nConnWritable(conn.handle))

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? {
        if (iter.isExhausted) return null
        val raw = nStreamIterNext(iter.handle)
        return if (raw >= 0) QuicStreamId(raw) else null
    }

    override fun streamIterFree(iter: QuicheStreamIter) {
        if (!iter.isExhausted) nStreamIterFree(iter.handle)
    }

    // --- Helpers ---
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo = QuicheRecvInfo(nRecvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen))

    override fun recvInfoFree(info: QuicheRecvInfo) = nRecvInfoFree(info.handle)

    override fun sendInfoNew(): QuicheSendInfo = QuicheSendInfo(nSendInfoNew())

    override fun sendInfoFree(info: QuicheSendInfo) = nSendInfoFree(info.handle)

    override fun sendInfoToAddr(info: QuicheSendInfo): Long = nSendInfoToAddr(info.handle)

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int = nSendInfoToAddrLen(info.handle)

    override fun sendInfoFromAddr(info: QuicheSendInfo): Long = nSendInfoFromAddr(info.handle)

    override fun sendInfoFromAddrLen(info: QuicheSendInfo): Int = nSendInfoFromAddrLen(info.handle)

    private const val QUICHE_ERR_DONE = -1L

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

    // --- Path migration JNI externals ---
    @JvmStatic private external fun nConnProbePath(
        conn: Long,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int

    @JvmStatic private external fun nConnMigrate(
        conn: Long,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int

    @JvmStatic private external fun nConnMigrateSource(
        conn: Long,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int

    @JvmStatic private external fun nConnAvailableDcids(conn: Long): Long

    @JvmStatic private external fun nConnScidsLeft(conn: Long): Long

    @JvmStatic private external fun nConnPathEventNext(
        conn: Long,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
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

    @JvmStatic private external fun nSendInfoFromAddr(ptr: Long): Long

    @JvmStatic private external fun nSendInfoFromAddrLen(ptr: Long): Int

    // --- Server-side JNI externals ---
    @JvmStatic private external fun nConfigLoadCertChainFromPemFile(
        config: Long,
        pathAddr: Long,
    ): Int

    @JvmStatic private external fun nConfigLoadPrivKeyFromPemFile(
        config: Long,
        pathAddr: Long,
    ): Int

    @JvmStatic private external fun nHeaderInfo(
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

    @JvmStatic private external fun nAccept(
        scidAddr: Long,
        scidLen: Int,
        odcidAddr: Long,
        odcidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: Long,
    ): Long

    @JvmStatic private external fun nNegotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int

    @FastNative
    @JvmStatic
    private external fun nConnReadable(conn: Long): Long

    @JvmStatic private external fun nConnWritable(conn: Long): Long

    @FastNative
    @JvmStatic
    private external fun nStreamIterNext(iter: Long): Long

    @JvmStatic private external fun nStreamIterFree(iter: Long)
}
