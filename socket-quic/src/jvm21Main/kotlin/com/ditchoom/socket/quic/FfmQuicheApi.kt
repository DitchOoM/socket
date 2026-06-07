@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.socket.quic

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_BYTE
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
    private val hSetActiveConnectionIdLimit by lazy {
        downcall("quiche_config_set_active_connection_id_limit", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))
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
    private val hConfigEnableDgram by lazy {
        downcall("quiche_config_enable_dgram", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG))
    }
    private val hConnDgramSend by lazy {
        downcall("quiche_conn_dgram_send", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG))
    }
    private val hConnDgramRecv by lazy {
        downcall("quiche_conn_dgram_recv", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG))
    }
    private val hConnDgramRecvFrontLen by lazy {
        downcall("quiche_conn_dgram_recv_front_len", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    }
    private val hConnDgramMaxWritableLen by lazy {
        downcall("quiche_conn_dgram_max_writable_len", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
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
    private val hStreamShutdown by lazy {
        // int quiche_conn_stream_shutdown(conn, uint64 stream_id, enum quiche_shutdown direction, uint64 err)
        downcall(
            "quiche_conn_stream_shutdown",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG),
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
    private val hSendAckEliciting by lazy {
        downcall("quiche_conn_send_ack_eliciting", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    }
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
    private val hConnNewScid by lazy {
        downcall(
            "quiche_conn_new_scid",
            // conn, scid*, scid_len(size_t), reset_token*, retire_if_needed(bool), scid_seq*
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_BOOLEAN, ADDRESS),
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
    private val hConnPathEventNext by lazy {
        downcall("quiche_conn_path_event_next", FunctionDescriptor.of(ADDRESS, ADDRESS))
    }
    private val hPathEventType by lazy {
        downcall("quiche_path_event_type", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }
    private val hPathEventNew by lazy {
        downcall("quiche_path_event_new", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val hPathEventValidated by lazy {
        downcall("quiche_path_event_validated", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val hPathEventFailedValidation by lazy {
        downcall("quiche_path_event_failed_validation", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val hPathEventClosed by lazy {
        downcall("quiche_path_event_closed", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val hPathEventPeerMigrated by lazy {
        downcall("quiche_path_event_peer_migrated", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val hPathEventFree by lazy {
        downcall("quiche_path_event_free", FunctionDescriptor.ofVoid(ADDRESS))
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

    override fun configSetActiveConnectionIdLimit(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetActiveConnectionIdLimit.invokeExact(seg(config.handle), v)
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

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int = hStreamShutdown.invokeExact(seg(conn.handle), streamId.id, direction, err) as Int

    // --- Unreliable datagrams (RFC 9221) ---

    override fun configEnableDgram(
        config: QuicheConfig,
        enabled: Boolean,
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) {
        hConfigEnableDgram.invokeExact(seg(config.handle), enabled, recvQueueLen, sendQueueLen)
    }

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = (hConnDgramSend.invokeExact(seg(conn.handle), seg(buf), bufLen.toLong()) as Long).toInt()

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        // ssize_t: >= 0 length (datagrams have no FIN), QUICHE_ERR_DONE when none queued, else error.
        val raw = hConnDgramRecv.invokeExact(seg(conn.handle), seg(buf), bufLen.toLong()) as Long
        return when {
            raw >= 0 -> StreamRecvResult.Data(raw.toInt(), false)
            raw == QUICHE_ERR_DONE -> StreamRecvResult.Done
            else -> StreamRecvResult.Error(raw.toInt())
        }
    }

    override fun hasReadableDgram(conn: QuicheConn): Boolean = (hConnDgramRecvFrontLen.invokeExact(seg(conn.handle)) as Long) >= 0

    override fun connDgramMaxWritableLen(conn: QuicheConn): MaxDatagramSize {
        val raw = hConnDgramMaxWritableLen.invokeExact(seg(conn.handle)) as Long
        return if (raw < 0) MaxDatagramSize.Unavailable else MaxDatagramSize.Bytes(raw.toInt())
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

    override fun connSendAckEliciting(conn: QuicheConn): Int = (hSendAckEliciting.invokeExact(seg(conn.handle)) as Long).toInt()

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

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int =
        hConnNewScid.invokeExact(
            seg(conn.handle),
            seg(scidAddr),
            scidLen.toLong(),
            seg(resetTokenAddr),
            retireIfNeeded,
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

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        val ev = hConnPathEventNext.invokeExact(seg(conn.handle)) as MemorySegment
        if (ev.address() == 0L) return null
        val type = hPathEventType.invokeExact(ev) as Int
        when (type) {
            0 -> hPathEventNew.invokeExact(ev, seg(localOut), seg(localLenOut), seg(peerOut), seg(peerLenOut))
            1 -> hPathEventValidated.invokeExact(ev, seg(localOut), seg(localLenOut), seg(peerOut), seg(peerLenOut))
            2 -> hPathEventFailedValidation.invokeExact(ev, seg(localOut), seg(localLenOut), seg(peerOut), seg(peerLenOut))
            3 -> hPathEventClosed.invokeExact(ev, seg(localOut), seg(localLenOut), seg(peerOut), seg(peerLenOut))
            4 -> {
                // ReusedSourceConnectionId: extra old/new-tuple + CID-seq fields out of scope; surface no addresses.
                seg(localLenOut).reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, 0)
                seg(peerLenOut).reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, 0)
            }
            5 -> hPathEventPeerMigrated.invokeExact(ev, seg(localOut), seg(localLenOut), seg(peerOut), seg(peerLenOut))
        }
        hPathEventFree.invokeExact(ev)
        return QuichePathEventType.entries[type]
    }

    // --- Server-side ---
    private val hLoadCert by lazy {
        downcall("quiche_config_load_cert_chain_from_pem_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }
    private val hLoadKey by lazy {
        downcall("quiche_config_load_priv_key_from_pem_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }
    private val hLoadVerify by lazy {
        downcall("quiche_config_load_verify_locations_from_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
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

    override fun configLoadVerifyLocationsFromFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = hLoadVerify.invokeExact(seg(config.handle), seg(pathAddr)) as Int

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
        // In quiche_send_info, `to` is an INLINE struct sockaddr_storage (unlike
        // quiche_recv_info, whose to/from are pointers). So its address is
        // `&info->to` = handle + offset, NOT a pointer value stored at that offset.
        // The old code read 8 bytes at the offset and treated them as a pointer,
        // yielding a garbage address that SIGSEGV'd in sockAddrFamily on every
        // flush (decodePathKey runs per-send). Mirror sendInfoFromAddr, which
        // returns the inline `from` at offset 0. Matches JNI nSendInfoToAddr (&info->to).
        return info.handle + SEND_INFO_TO_OFFSET
    }

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int {
        val segment = MemorySegment.ofAddress(info.handle).reinterpret(SEND_INFO_SIZE.toLong())
        return segment.get(JAVA_INT, SEND_INFO_TO_LEN_OFFSET.toLong())
    }

    override fun sendInfoFromAddr(info: QuicheSendInfo): Long {
        // from sockaddr_storage is the first field of quiche_send_info (offset 0).
        return info.handle
    }

    override fun sendInfoFromAddrLen(info: QuicheSendInfo): Int {
        val segment = MemorySegment.ofAddress(info.handle).reinterpret(SEND_INFO_SIZE.toLong())
        return segment.get(JAVA_INT, SEND_INFO_FROM_LEN_OFFSET.toLong())
    }

    // --- sockaddr decode (slice 3 migration) ---
    // FFM reads the struct directly. The JVM runs on Linux/macOS/Windows, so this
    // must mirror SockAddrUtil's encode layout: BSD puts sin_len at byte 0 and
    // sa_family at byte 1; Linux/Windows put sa_family as a uint16 at byte 0.
    // AF_INET = 2 everywhere; AF_INET6 = 10 (Linux) / 30 (BSD) / 23 (Windows).
    // sin_port is at offset 2, sin_addr at 4, sin6_addr at 8 in both layouts.

    private fun ss(addr: Long): MemorySegment = MemorySegment.ofAddress(addr).reinterpret(SOCKADDR_STORAGE_SIZE)

    private fun u8(
        seg: MemorySegment,
        off: Int,
    ): Int = seg.get(JAVA_BYTE, off.toLong()).toInt() and 0xFF

    private fun beLong(
        seg: MemorySegment,
        off: Int,
    ): Long {
        var v = 0L
        for (i in off until off + 8) v = (v shl 8) or u8(seg, i).toLong()
        return v
    }

    override fun sockAddrFamily(addr: Long): Int {
        val seg = ss(addr)
        val fam = if (IS_BSD) u8(seg, 1) else u8(seg, 0) or (u8(seg, 1) shl 8)
        return when (fam) {
            AF_INET -> 4
            AF_INET6 -> 6
            else -> 0
        }
    }

    override fun sockAddrPort(addr: Long): Int {
        val seg = ss(addr)
        return (u8(seg, 2) shl 8) or u8(seg, 3)
    }

    override fun sockAddrV4(addr: Long): Long {
        val seg = ss(addr)
        var v = 0L
        for (i in 4 until 8) v = (v shl 8) or u8(seg, i).toLong()
        return v
    }

    override fun sockAddrV6Hi(addr: Long): Long = beLong(ss(addr), 8)

    override fun sockAddrV6Lo(addr: Long): Long = beLong(ss(addr), 16)

    companion object {
        private const val SOCKADDR_STORAGE_SIZE = 128L
        private const val AF_INET = 2
        private val IS_BSD: Boolean =
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") || it.contains("bsd") }
        private val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
        private val AF_INET6: Int =
            when {
                IS_BSD -> 30
                IS_WINDOWS -> 23
                else -> 10
            }

        private const val QUICHE_ERR_DONE = -1L
        private const val RECV_INFO_SIZE = 32
        private const val SEND_INFO_SIZE = 288
        private const val SEND_INFO_TO_OFFSET = 136 // after sockaddr_storage(128) + socklen_t(4) + pad(4)
        private const val SEND_INFO_TO_LEN_OFFSET = 264 // after to sockaddr_storage(128)
        private const val SEND_INFO_FROM_LEN_OFFSET = 128 // after from sockaddr_storage(128)

        fun create(libraryPath: String): FfmQuicheApi {
            val arena = Arena.ofAuto()
            val lookup = SymbolLookup.libraryLookup(libraryPath, arena)
            val linker = Linker.nativeLinker()
            return FfmQuicheApi(lookup, linker)
        }
    }
}
