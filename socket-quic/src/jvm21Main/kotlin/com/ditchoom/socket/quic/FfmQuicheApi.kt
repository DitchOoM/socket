@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.socket.quic

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * FFM quiche bindings for JDK 21+.
 *
 * Pure zero-copy: every parameter is a native address. Value classes ([QuicheConfig],
 * [QuicheConn], etc.) are erased to [Long] by the compiler — no boxing overhead.
 */
class FfmQuicheApi private constructor(
    private val lookup: SymbolLookup,
    private val linker: Linker,
) : QuicheApi {
    private fun downcall(
        name: String,
        desc: FunctionDescriptor,
    ): MethodHandle {
        val symbol = lookup.find(name).orElseThrow { UnsatisfiedLinkError("quiche: $name") }
        return linker.downcallHandle(symbol, desc)
    }

    private fun seg(addr: Long): MemorySegment = MemorySegment.ofAddress(addr)

    // --- Lazily resolved downcall handles ---
    private val hConfigNew by lazy { downcall("quiche_config_new", FunctionDescriptor.of(ADDRESS, JAVA_INT)) }
    private val hConfigFree by lazy { downcall("quiche_config_free", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val hConfigSetAppProtos by lazy {
        downcall("quiche_config_set_application_protos", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG))
    }
    private val hConfigSetMaxIdleTimeout by lazy {
        downcall("quiche_config_set_max_idle_timeout", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hConfigSetMaxRecvUdp by lazy {
        downcall("quiche_config_set_max_recv_udp_payload_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hConfigSetMaxSendUdp by lazy {
        downcall("quiche_config_set_max_send_udp_payload_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetMaxData by lazy {
        downcall("quiche_config_set_initial_max_data", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetBidiLocal by lazy {
        downcall("quiche_config_set_initial_max_stream_data_bidi_local", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetBidiRemote by lazy {
        downcall("quiche_config_set_initial_max_stream_data_bidi_remote", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetUni by lazy {
        downcall("quiche_config_set_initial_max_stream_data_uni", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetStreamsBidi by lazy {
        downcall("quiche_config_set_initial_max_streams_bidi", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetStreamsUni by lazy {
        downcall("quiche_config_set_initial_max_streams_uni", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hDisableMigration by lazy {
        downcall("quiche_config_set_disable_active_migration", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN))
    }
    private val hVerifyPeer by lazy {
        downcall("quiche_config_verify_peer", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN))
    }
    private val hEnablePacing by lazy {
        downcall("quiche_config_enable_pacing", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN))
    }
    private val hSetMaxPacingRate by lazy {
        downcall("quiche_config_set_max_pacing_rate", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetCcAlgorithm by lazy {
        downcall("quiche_config_set_cc_algorithm", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))
    }
    private val hEnableHystart by lazy {
        downcall("quiche_config_enable_hystart", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN))
    }
    private val hSetInitialCwndPackets by lazy {
        downcall("quiche_config_set_initial_congestion_window_packets", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetMaxConnWindow by lazy {
        downcall("quiche_config_set_max_connection_window", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hSetMaxStreamWindow by lazy {
        downcall("quiche_config_set_max_stream_window", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
    }
    private val hDiscoverPmtu by lazy {
        downcall("quiche_config_discover_pmtu", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN))
    }
    private val hEnableEarlyData by lazy {
        downcall("quiche_config_enable_early_data", FunctionDescriptor.ofVoid(ADDRESS))
    }
    private val hGrease by lazy {
        downcall("quiche_config_grease", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN))
    }
    private val hConnect by lazy {
        downcall(
            "quiche_connect",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        )
    }
    private val hConnFree by lazy { downcall("quiche_conn_free", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val hConnRecv by lazy {
        downcall("quiche_conn_recv", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
    }
    private val hConnSend by lazy {
        downcall("quiche_conn_send", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
    }
    private val hStreamRecv by lazy {
        downcall(
            "quiche_conn_stream_recv",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS),
        )
    }
    private val hStreamSend by lazy {
        downcall(
            "quiche_conn_stream_send",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_BOOLEAN, ADDRESS),
        )
    }
    private val hIsEstablished by lazy {
        downcall("quiche_conn_is_established", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS))
    }
    private val hIsClosed by lazy {
        downcall("quiche_conn_is_closed", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS))
    }
    private val hIsTimedOut by lazy {
        downcall("quiche_conn_is_timed_out", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS))
    }
    private val hTimeoutNanos by lazy {
        downcall("quiche_conn_timeout_as_nanos", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    }
    private val hOnTimeout by lazy { downcall("quiche_conn_on_timeout", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val hConnClose by lazy {
        downcall(
            "quiche_conn_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BOOLEAN, JAVA_LONG, ADDRESS, JAVA_LONG),
        )
    }

    // --- Path migration handles ---
    private val hConnProbePath by lazy {
        downcall(
            "quiche_conn_probe_path",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        )
    }
    private val hConnMigrate by lazy {
        downcall(
            "quiche_conn_migrate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        )
    }
    private val hConnMigrateSource by lazy {
        downcall(
            "quiche_conn_migrate_source",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
        )
    }
    private val hConnAvailableDcids by lazy {
        downcall("quiche_conn_available_dcids", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    }
    private val hConnScidsLeft by lazy {
        downcall("quiche_conn_scids_left", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    }

    // --- Config ---
    override fun configNew(version: Int): QuicheConfig = QuicheConfig((hConfigNew.invokeExact(version) as MemorySegment).address())

    override fun configFree(config: QuicheConfig) {
        hConfigFree.invokeExact(seg(config.handle))
    }

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = hConfigSetAppProtos.invokeExact(seg(config.handle), seg(protosAddr), protosLen.toLong()) as Int

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) {
        hConfigSetMaxIdleTimeout.invokeExact(seg(config.handle), timeout)
    }

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        hConfigSetMaxRecvUdp.invokeExact(seg(config.handle), size)
    }

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        hConfigSetMaxSendUdp.invokeExact(seg(config.handle), size)
    }

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetMaxData.invokeExact(seg(config.handle), v)
    }

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetBidiLocal.invokeExact(seg(config.handle), v)
    }

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetBidiRemote.invokeExact(seg(config.handle), v)
    }

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetUni.invokeExact(seg(config.handle), v)
    }

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetStreamsBidi.invokeExact(seg(config.handle), v)
    }

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetStreamsUni.invokeExact(seg(config.handle), v)
    }

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hDisableMigration.invokeExact(seg(config.handle), v)
    }

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hVerifyPeer.invokeExact(seg(config.handle), v)
    }

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hEnablePacing.invokeExact(seg(config.handle), v)
    }

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetMaxPacingRate.invokeExact(seg(config.handle), v)
    }

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) {
        hSetCcAlgorithm.invokeExact(seg(config.handle), algo)
    }

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hEnableHystart.invokeExact(seg(config.handle), v)
    }

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) {
        hSetInitialCwndPackets.invokeExact(seg(config.handle), packets)
    }

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetMaxConnWindow.invokeExact(seg(config.handle), v)
    }

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetMaxStreamWindow.invokeExact(seg(config.handle), v)
    }

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hDiscoverPmtu.invokeExact(seg(config.handle), v)
    }

    override fun configEnableEarlyData(config: QuicheConfig) {
        hEnableEarlyData.invokeExact(seg(config.handle))
    }

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hGrease.invokeExact(seg(config.handle), v)
    }

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
    ): QuicheConn {
        val result =
            hConnect.invokeExact(
                seg(serverNameAddr),
                seg(scidAddr),
                scidLen.toLong(),
                seg(localAddr),
                localAddrLen,
                seg(peerAddr),
                peerAddrLen,
                seg(config.handle),
            ) as MemorySegment
        return QuicheConn(result.address())
    }

    override fun connFree(conn: QuicheConn) {
        hConnFree.invokeExact(seg(conn.handle))
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = (hConnRecv.invokeExact(seg(conn.handle), seg(buf), bufLen.toLong(), seg(recvInfo.handle)) as Long).toInt()

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = (hConnSend.invokeExact(seg(conn.handle), seg(buf), bufLen.toLong(), seg(sendInfo.handle)) as Long).toInt()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult =
        Arena.ofConfined().use { arena ->
            val finOut = arena.allocate(JAVA_BOOLEAN)
            val errOut = arena.allocate(JAVA_LONG)
            val raw = hStreamRecv.invokeExact(seg(conn.handle), streamId.id, seg(buf), bufLen.toLong(), finOut, errOut) as Long
            if (raw >= 0) {
                val fin = finOut.get(JAVA_BOOLEAN, 0)
                StreamRecvResult.Data(raw.toInt(), fin)
            } else if (raw == QUICHE_ERR_DONE) {
                StreamRecvResult.Done
            } else {
                StreamRecvResult.Error(raw.toInt())
            }
        }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int =
        Arena.ofConfined().use { arena ->
            val errOut = arena.allocate(JAVA_LONG)
            (hStreamSend.invokeExact(seg(conn.handle), streamId.id, seg(buf), bufLen.toLong(), fin, errOut) as Long).toInt()
        }

    override fun connIsEstablished(conn: QuicheConn): Boolean = hIsEstablished.invokeExact(seg(conn.handle)) as Boolean

    override fun connIsClosed(conn: QuicheConn): Boolean = hIsClosed.invokeExact(seg(conn.handle)) as Boolean

    override fun connIsTimedOut(conn: QuicheConn): Boolean = hIsTimedOut.invokeExact(seg(conn.handle)) as Boolean

    override fun connTimeout(conn: QuicheConn): Duration? {
        val nanos = hTimeoutNanos.invokeExact(seg(conn.handle)) as Long
        return if (nanos < 0) null else nanos.nanoseconds
    }

    override fun connOnTimeout(conn: QuicheConn) {
        hOnTimeout.invokeExact(seg(conn.handle))
    }

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int =
        hConnClose.invokeExact(
            seg(conn.handle),
            error is QuicError.ApplicationError,
            error.code,
            seg(0L),
            0L,
        ) as Int

    // --- Path migration ---
    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int =
        hConnProbePath.invokeExact(
            seg(conn.handle),
            seg(localAddr),
            localLen,
            seg(peerAddr),
            peerLen,
            seg(seqOut),
        ) as Int

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int =
        hConnMigrate.invokeExact(
            seg(conn.handle),
            seg(localAddr),
            localLen,
            seg(peerAddr),
            peerLen,
            seg(seqOut),
        ) as Int

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int =
        hConnMigrateSource.invokeExact(
            seg(conn.handle),
            seg(localAddr),
            localLen,
            seg(seqOut),
        ) as Int

    override fun connAvailableDcids(conn: QuicheConn): Long = hConnAvailableDcids.invokeExact(seg(conn.handle)) as Long

    override fun connScidsLeft(conn: QuicheConn): Long = hConnScidsLeft.invokeExact(seg(conn.handle)) as Long

    // --- Server-side ---
    private val hLoadCert by lazy {
        downcall("quiche_config_load_cert_chain_from_pem_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }
    private val hLoadKey by lazy {
        downcall("quiche_config_load_priv_key_from_pem_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }
    private val hHeaderInfo by lazy {
        downcall(
            "quiche_header_info",
            FunctionDescriptor.of(
                JAVA_INT,
                ADDRESS,
                JAVA_LONG,
                JAVA_LONG,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                ADDRESS,
            ),
        )
    }
    private val hAccept by lazy {
        downcall(
            "quiche_accept",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        )
    }
    private val hNegotiateVersion by lazy {
        downcall(
            "quiche_negotiate_version",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG),
        )
    }
    private val hConnReadable by lazy {
        downcall("quiche_conn_readable", FunctionDescriptor.of(ADDRESS, ADDRESS))
    }
    private val hConnWritable by lazy {
        downcall("quiche_conn_writable", FunctionDescriptor.of(ADDRESS, ADDRESS))
    }
    private val hStreamIterNext by lazy {
        downcall("quiche_stream_iter_next", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS))
    }
    private val hStreamIterFree by lazy {
        downcall("quiche_stream_iter_free", FunctionDescriptor.ofVoid(ADDRESS))
    }

    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = hLoadCert.invokeExact(seg(config.handle), seg(pathAddr)) as Int

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = hLoadKey.invokeExact(seg(config.handle), seg(pathAddr)) as Int

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
        hHeaderInfo.invokeExact(
            seg(buf),
            bufLen.toLong(),
            dcil.toLong(),
            seg(versionOut),
            seg(typeOut),
            seg(scidOut),
            seg(scidLenOut),
            seg(dcidOut),
            seg(dcidLenOut),
            seg(tokenOut),
            seg(tokenLenOut),
        ) as Int

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
    ): QuicheConn {
        val result =
            hAccept.invokeExact(
                seg(scidAddr),
                scidLen.toLong(),
                seg(odcidAddr),
                odcidLen.toLong(),
                seg(localAddr),
                localAddrLen,
                seg(peerAddr),
                peerAddrLen,
                seg(config.handle),
            ) as MemorySegment
        return QuicheConn(result.address())
    }

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int =
        (
            hNegotiateVersion.invokeExact(
                seg(scidAddr),
                scidLen.toLong(),
                seg(dcidAddr),
                dcidLen.toLong(),
                seg(outAddr),
                outLen.toLong(),
            ) as Long
        ).toInt()

    override fun connReadable(conn: QuicheConn): QuicheStreamIter =
        QuicheStreamIter((hConnReadable.invokeExact(seg(conn.handle)) as MemorySegment).address())

    override fun connWritable(conn: QuicheConn): QuicheStreamIter =
        QuicheStreamIter((hConnWritable.invokeExact(seg(conn.handle)) as MemorySegment).address())

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? {
        if (iter.isExhausted) return null
        return Arena.ofConfined().use { arena ->
            val streamIdOut = arena.allocate(JAVA_LONG)
            val hasNext = hStreamIterNext.invokeExact(seg(iter.handle), streamIdOut) as Boolean
            if (hasNext) QuicStreamId(streamIdOut.get(JAVA_LONG, 0)) else null
        }
    }

    override fun streamIterFree(iter: QuicheStreamIter) {
        if (!iter.isExhausted) hStreamIterFree.invokeExact(seg(iter.handle))
    }

    // --- RecvInfo / SendInfo via FFM Arena-allocated structs ---
    //
    // quiche_recv_info layout (64-bit):
    //   [0..7]   from: sockaddr*     (ADDRESS)
    //   [8..11]  from_len: socklen_t (JAVA_INT) + 4 bytes padding
    //   [16..23] to: sockaddr*       (ADDRESS)
    //   [24..27] to_len: socklen_t   (JAVA_INT) + 4 bytes padding
    //   Total: 32 bytes
    //
    // quiche_send_info layout (64-bit):
    //   [0..127]   from: sockaddr_storage (128 bytes)
    //   [128..131] from_len: socklen_t    + padding to 136
    //   [136..263] to: sockaddr_storage   (128 bytes)
    //   [264..267] to_len: socklen_t      + padding to 272
    //   [272..287] at: timespec           (16 bytes)
    //   Total: 288 bytes

    private val recvInfoArena = Arena.ofAuto()
    private val sendInfoArena = Arena.ofAuto()

    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo {
        val info = recvInfoArena.allocate(RECV_INFO_SIZE.toLong(), 8)
        info.set(ADDRESS, 0, seg(fromAddr)) // from
        info.set(JAVA_INT, 8, fromAddrLen) // from_len
        info.set(ADDRESS, 16, seg(toAddr)) // to
        info.set(JAVA_INT, 24, toAddrLen) // to_len
        return QuicheRecvInfo(info.address())
    }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        // Arena.ofAuto manages lifecycle — no manual free needed
    }

    override fun sendInfoNew(): QuicheSendInfo {
        val info = sendInfoArena.allocate(SEND_INFO_SIZE.toLong(), 8)
        return QuicheSendInfo(info.address())
    }

    override fun sendInfoFree(info: QuicheSendInfo) {
        // Arena.ofAuto manages lifecycle
    }

    override fun sendInfoToAddr(info: QuicheSendInfo): Long {
        // to field starts at offset 136 (after from sockaddr_storage 128 + from_len 4 + padding 4)
        val segment = MemorySegment.ofAddress(info.handle).reinterpret(SEND_INFO_SIZE.toLong())
        return segment.get(ADDRESS, SEND_INFO_TO_OFFSET.toLong()).address()
    }

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int {
        val segment = MemorySegment.ofAddress(info.handle).reinterpret(SEND_INFO_SIZE.toLong())
        return segment.get(JAVA_INT, SEND_INFO_TO_LEN_OFFSET.toLong())
    }

    companion object {
        private const val QUICHE_ERR_DONE = -1L
        private const val RECV_INFO_SIZE = 32
        private const val SEND_INFO_SIZE = 288
        private const val SEND_INFO_TO_OFFSET = 136 // after sockaddr_storage(128) + socklen_t(4) + pad(4)
        private const val SEND_INFO_TO_LEN_OFFSET = 264 // after to sockaddr_storage(128)

        fun create(libraryPath: String): FfmQuicheApi {
            val arena = Arena.ofAuto()
            val lookup = SymbolLookup.libraryLookup(libraryPath, arena)
            val linker = Linker.nativeLinker()
            return FfmQuicheApi(lookup, linker)
        }
    }
}
