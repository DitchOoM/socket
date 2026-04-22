package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * Node.js [QuicheApi] backed by koffi FFI against libquiche.{so,dylib}.
 *
 * **Status:** scaffold. Every method throws [NotImplementedError] pending the
 * koffi binding work (Phase 3.2 JS implementation). See [Koffi] for the loader.
 *
 * Implementation strategy mirrors [com.ditchoom.socket.quic.QuicheDriver]'s
 * call shape — purely synchronous calls into quiche's C API. koffi handles
 * pointer types (Numbers) and struct marshaling. Unlike the JVM path, JS
 * buffers have no `nativeMemoryAccess`, so bytes must be copied between
 * JS `Uint8Array` and koffi-allocated native memory on every hop.
 *
 * Will be instantiated by [com.ditchoom.socket.quic.JsQuicEngine] only when
 * [isNode] is true; browser code never reaches this class.
 */
internal class KoffiQuicheApi : QuicheApi {
    // Config
    override fun configNew(version: Int): QuicheConfig = TODO("koffi: quiche_config_new")

    override fun configFree(config: QuicheConfig): Unit = TODO("koffi: quiche_config_free")

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = TODO("koffi: quiche_config_set_application_protos")

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ): Unit = TODO("koffi: quiche_config_set_max_idle_timeout")

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ): Unit = TODO("koffi: quiche_config_set_max_recv_udp_payload_size")

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ): Unit = TODO("koffi: quiche_config_set_max_send_udp_payload_size")

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_max_data")

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_max_stream_data_bidi_local")

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_max_stream_data_bidi_remote")

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_max_stream_data_uni")

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_max_streams_bidi")

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_max_streams_uni")

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ): Unit = TODO("koffi: quiche_config_set_disable_active_migration")

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ): Unit = TODO("koffi: quiche_config_verify_peer")

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ): Unit = TODO("koffi: quiche_config_enable_pacing")

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_max_pacing_rate")

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ): Unit = TODO("koffi: quiche_config_set_cc_algorithm")

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ): Unit = TODO("koffi: quiche_config_enable_hystart")

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ): Unit = TODO("koffi: quiche_config_set_initial_congestion_window_packets")

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_max_connection_window")

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ): Unit = TODO("koffi: quiche_config_set_max_stream_window")

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ): Unit = TODO("koffi: quiche_config_discover_pmtu")

    override fun configEnableEarlyData(config: QuicheConfig): Unit = TODO("koffi: quiche_config_enable_early_data")

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ): Unit = TODO("koffi: quiche_config_grease")

    // Connection
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
    ): QuicheConn = TODO("koffi: quiche_connect")

    override fun connFree(conn: QuicheConn): Unit = TODO("koffi: quiche_conn_free")

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = TODO("koffi: quiche_conn_recv")

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = TODO("koffi: quiche_conn_send")

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult = TODO("koffi: quiche_conn_stream_recv")

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int = TODO("koffi: quiche_conn_stream_send")

    override fun connIsEstablished(conn: QuicheConn): Boolean = TODO("koffi: quiche_conn_is_established")

    override fun connIsClosed(conn: QuicheConn): Boolean = TODO("koffi: quiche_conn_is_closed")

    override fun connIsTimedOut(conn: QuicheConn): Boolean = TODO("koffi: quiche_conn_is_timed_out")

    override fun connTimeout(conn: QuicheConn): Duration? = TODO("koffi: quiche_conn_timeout_as_nanos")

    override fun connOnTimeout(conn: QuicheConn): Unit = TODO("koffi: quiche_conn_on_timeout")

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int = TODO("koffi: quiche_conn_close")

    // Server
    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = TODO("koffi: quiche_config_load_cert_chain_from_pem_file")

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = TODO("koffi: quiche_config_load_priv_key_from_pem_file")

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
    ): Int = TODO("koffi: quiche_header_info")

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
    ): QuicheConn = TODO("koffi: quiche_accept")

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int = TODO("koffi: quiche_negotiate_version")

    // Stream iteration
    override fun connReadable(conn: QuicheConn): QuicheStreamIter = TODO("koffi: quiche_conn_readable")

    override fun connWritable(conn: QuicheConn): QuicheStreamIter = TODO("koffi: quiche_conn_writable")

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? = TODO("koffi: quiche_stream_iter_next")

    override fun streamIterFree(iter: QuicheStreamIter): Unit = TODO("koffi: quiche_stream_iter_free")

    // Helpers
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo = TODO("koffi: build quiche_recv_info struct")

    override fun recvInfoFree(info: QuicheRecvInfo): Unit = TODO("koffi: free quiche_recv_info struct")

    override fun sendInfoNew(): QuicheSendInfo = TODO("koffi: allocate quiche_send_info struct")

    override fun sendInfoFree(info: QuicheSendInfo): Unit = TODO("koffi: free quiche_send_info struct")

    override fun sendInfoToAddr(info: QuicheSendInfo): Long = TODO("koffi: read send_info.to pointer")

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int = TODO("koffi: read send_info.to_len")
}
