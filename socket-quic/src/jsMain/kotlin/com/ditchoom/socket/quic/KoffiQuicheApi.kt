package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

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
    // Cached koffi function handles. Binding via lib.func() parses the C prototype
    // string once per signature; we memoize at construction so the hot path in
    // QuicheDriver doesn't re-parse on every call.
    private val fnConfigNew = quicheLibrary.func("void* quiche_config_new(uint32_t version)")
    private val fnConfigFree = quicheLibrary.func("void quiche_config_free(void* config)")
    private val fnConfigSetApplicationProtos =
        quicheLibrary.func(
            "int quiche_config_set_application_protos(void* config, const uint8_t* protos, size_t protos_len)",
        )
    private val fnConfigSetMaxIdleTimeout =
        quicheLibrary.func("void quiche_config_set_max_idle_timeout(void* config, uint64_t v)")
    private val fnConfigSetMaxRecvUdpPayloadSize =
        quicheLibrary.func("void quiche_config_set_max_recv_udp_payload_size(void* config, size_t v)")
    private val fnConfigSetMaxSendUdpPayloadSize =
        quicheLibrary.func("void quiche_config_set_max_send_udp_payload_size(void* config, size_t v)")
    private val fnConfigSetInitialMaxData =
        quicheLibrary.func("void quiche_config_set_initial_max_data(void* config, uint64_t v)")
    private val fnConfigSetInitialMaxStreamDataBidiLocal =
        quicheLibrary.func("void quiche_config_set_initial_max_stream_data_bidi_local(void* config, uint64_t v)")
    private val fnConfigSetInitialMaxStreamDataBidiRemote =
        quicheLibrary.func("void quiche_config_set_initial_max_stream_data_bidi_remote(void* config, uint64_t v)")
    private val fnConfigSetInitialMaxStreamDataUni =
        quicheLibrary.func("void quiche_config_set_initial_max_stream_data_uni(void* config, uint64_t v)")
    private val fnConfigSetInitialMaxStreamsBidi =
        quicheLibrary.func("void quiche_config_set_initial_max_streams_bidi(void* config, uint64_t v)")
    private val fnConfigSetInitialMaxStreamsUni =
        quicheLibrary.func("void quiche_config_set_initial_max_streams_uni(void* config, uint64_t v)")
    private val fnConfigSetDisableActiveMigration =
        quicheLibrary.func("void quiche_config_set_disable_active_migration(void* config, bool v)")
    private val fnConfigVerifyPeer = quicheLibrary.func("void quiche_config_verify_peer(void* config, bool v)")
    private val fnConfigEnablePacing = quicheLibrary.func("void quiche_config_enable_pacing(void* config, bool v)")
    private val fnConfigSetMaxPacingRate =
        quicheLibrary.func("void quiche_config_set_max_pacing_rate(void* config, uint64_t v)")
    private val fnConfigSetCcAlgorithm =
        quicheLibrary.func("void quiche_config_set_cc_algorithm(void* config, int algo)")
    private val fnConfigEnableHystart = quicheLibrary.func("void quiche_config_enable_hystart(void* config, bool v)")
    private val fnConfigSetInitialCongestionWindowPackets =
        quicheLibrary.func("void quiche_config_set_initial_congestion_window_packets(void* config, size_t packets)")
    private val fnConfigSetMaxConnectionWindow =
        quicheLibrary.func("void quiche_config_set_max_connection_window(void* config, uint64_t v)")
    private val fnConfigSetMaxStreamWindow =
        quicheLibrary.func("void quiche_config_set_max_stream_window(void* config, uint64_t v)")
    private val fnConfigDiscoverPmtu =
        quicheLibrary.func("void quiche_config_discover_pmtu(void* config, bool v)")
    private val fnConfigEnableEarlyData =
        quicheLibrary.func("void quiche_config_enable_early_data(void* config)")
    private val fnConfigGrease = quicheLibrary.func("void quiche_config_grease(void* config, bool v)")
    private val fnConnFree = quicheLibrary.func("void quiche_conn_free(void* conn)")
    private val fnConnIsEstablished = quicheLibrary.func("bool quiche_conn_is_established(void* conn)")
    private val fnConnIsClosed = quicheLibrary.func("bool quiche_conn_is_closed(void* conn)")
    private val fnConnIsTimedOut = quicheLibrary.func("bool quiche_conn_is_timed_out(void* conn)")
    private val fnConnTimeoutAsNanos = quicheLibrary.func("uint64_t quiche_conn_timeout_as_nanos(void* conn)")
    private val fnConnOnTimeout = quicheLibrary.func("void quiche_conn_on_timeout(void* conn)")
    private val fnConnClose =
        quicheLibrary.func(
            "int quiche_conn_close(void* conn, bool app, uint64_t err, const uint8_t* reason, size_t reason_len)",
        )

    // Config
    override fun configNew(version: Int): QuicheConfig = QuicheConfig(addressOf(fnConfigNew(version)))

    override fun configFree(config: QuicheConfig) {
        fnConfigFree(config.handle.asPointer())
    }

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = fnConfigSetApplicationProtos(config.handle.asPointer(), protosAddr.asPointer(), protosLen).unsafeCast<Int>()

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) {
        fnConfigSetMaxIdleTimeout(config.handle.asPointer(), timeout.asPointer())
    }

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        fnConfigSetMaxRecvUdpPayloadSize(config.handle.asPointer(), size.asPointer())
    }

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        fnConfigSetMaxSendUdpPayloadSize(config.handle.asPointer(), size.asPointer())
    }

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetInitialMaxData(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetInitialMaxStreamDataBidiLocal(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetInitialMaxStreamDataBidiRemote(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetInitialMaxStreamDataUni(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetInitialMaxStreamsBidi(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetInitialMaxStreamsUni(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) {
        fnConfigSetDisableActiveMigration(config.handle.asPointer(), v)
    }

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) {
        fnConfigVerifyPeer(config.handle.asPointer(), v)
    }

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) {
        fnConfigEnablePacing(config.handle.asPointer(), v)
    }

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetMaxPacingRate(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) {
        fnConfigSetCcAlgorithm(config.handle.asPointer(), algo)
    }

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) {
        fnConfigEnableHystart(config.handle.asPointer(), v)
    }

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) {
        fnConfigSetInitialCongestionWindowPackets(config.handle.asPointer(), packets.asPointer())
    }

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetMaxConnectionWindow(config.handle.asPointer(), v.asPointer())
    }

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) {
        fnConfigSetMaxStreamWindow(config.handle.asPointer(), v.asPointer())
    }

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) {
        fnConfigDiscoverPmtu(config.handle.asPointer(), v)
    }

    override fun configEnableEarlyData(config: QuicheConfig) {
        fnConfigEnableEarlyData(config.handle.asPointer())
    }

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) {
        fnConfigGrease(config.handle.asPointer(), v)
    }

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

    override fun connFree(conn: QuicheConn) {
        fnConnFree(conn.handle.asPointer())
    }

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

    override fun connIsEstablished(conn: QuicheConn): Boolean =
        fnConnIsEstablished(conn.handle.asPointer()).unsafeCast<Boolean>()

    override fun connIsClosed(conn: QuicheConn): Boolean = fnConnIsClosed(conn.handle.asPointer()).unsafeCast<Boolean>()

    override fun connIsTimedOut(conn: QuicheConn): Boolean =
        fnConnIsTimedOut(conn.handle.asPointer()).unsafeCast<Boolean>()

    override fun connTimeout(conn: QuicheConn): Duration? {
        val raw = fnConnTimeoutAsNanos(conn.handle.asPointer())
        // koffi returns uint64_t as Number when the value fits in 2^53, BigInt otherwise.
        // quiche's sentinel for "no timeout" is UINT64_MAX — always BigInt, always overflows Long.
        // Any real timeout nanoseconds fits easily in Long (2^62 ns = 146 years).
        val nanos = raw.toString().unsafeCast<String>().toLongOrNull() ?: return null
        return if (nanos < 0L) null else nanos.nanoseconds
    }

    override fun connOnTimeout(conn: QuicheConn) {
        fnConnOnTimeout(conn.handle.asPointer())
    }

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int {
        val app = error is QuicError.ApplicationError
        // No reason bytes for now — mirrors JniQuicheApi which also passes (0, 0). Extending
        // the QuicError surface to carry a reason string is a separate change.
        return fnConnClose(
            conn.handle.asPointer(),
            app,
            error.code.asPointer(),
            0L.asPointer(),
            0,
        ).unsafeCast<Int>()
    }

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
