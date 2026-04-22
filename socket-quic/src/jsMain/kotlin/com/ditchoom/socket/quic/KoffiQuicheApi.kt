package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** quiche sentinel for "no more data" / "stream finished" on ssize_t-returning I/O. */
private const val QUICHE_ERR_DONE: Int = -1

/**
 * Node.js [QuicheApi] backed by koffi FFI against libquiche.{so,dylib}.
 *
 * Implementation strategy mirrors [com.ditchoom.socket.quic.QuicheDriver]'s
 * call shape — purely synchronous calls into quiche's C API. koffi handles
 * pointer types (Numbers) and struct marshaling. Unlike the JVM path, JS
 * buffers have no `nativeMemoryAccess`, so bytes must be copied between
 * JS `Uint8Array` and koffi-allocated native memory on every hop.
 *
 * Instantiated by [JsQuicEngine] only when [isNode] is true; browser code
 * never reaches this class.
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
    private val fnConnReadable = quicheLibrary.func("void* quiche_conn_readable(void* conn)")
    private val fnConnWritable = quicheLibrary.func("void* quiche_conn_writable(void* conn)")
    private val fnStreamIterNext =
        quicheLibrary.func("bool quiche_stream_iter_next(void* iter, uint64_t* stream_id)")
    private val fnStreamIterFree = quicheLibrary.func("void quiche_stream_iter_free(void* iter)")
    private val fnConnRecv =
        quicheLibrary.func("int quiche_conn_recv(void* conn, uint8_t* buf, size_t buf_len, const void* info)")
    private val fnConnSend =
        quicheLibrary.func("int quiche_conn_send(void* conn, uint8_t* out, size_t out_len, void* out_info)")

    // quiche 0.28's stream_recv/send each take a trailing `uint64_t *out_error_code` output param
    // that is only populated on STOP_SENDING / STREAM_RESET. The QuicheApi interface doesn't expose
    // it; we pass a 1-slot BigInt64Array scratch buffer and discard the value — matches JniQuicheApi
    // behavior (the JNI shim passes a local and drops it).
    private val fnConnStreamRecv =
        quicheLibrary.func(
            "int quiche_conn_stream_recv(void* conn, uint64_t stream_id, uint8_t* out, " +
                "size_t buf_len, bool* fin, uint64_t* out_error_code)",
        )
    private val fnConnStreamSend =
        quicheLibrary.func(
            "int quiche_conn_stream_send(void* conn, uint64_t stream_id, const uint8_t* buf, " +
                "size_t buf_len, bool fin, uint64_t* out_error_code)",
        )
    private val fnConnect =
        quicheLibrary.func(
            "void* quiche_connect(const char* server_name, const uint8_t* scid, size_t scid_len, " +
                "const void* local, uint32_t local_len, const void* peer, uint32_t peer_len, void* config)",
        )
    private val fnAccept =
        quicheLibrary.func(
            "void* quiche_accept(const uint8_t* scid, size_t scid_len, const uint8_t* odcid, size_t odcid_len, " +
                "const void* local, uint32_t local_len, const void* peer, uint32_t peer_len, void* config)",
        )
    private val fnNegotiateVersion =
        quicheLibrary.func(
            "int quiche_negotiate_version(const uint8_t* scid, size_t scid_len, " +
                "const uint8_t* dcid, size_t dcid_len, uint8_t* out, size_t out_len)",
        )
    private val fnHeaderInfo =
        quicheLibrary.func(
            "int quiche_header_info(const uint8_t* buf, size_t buf_len, size_t dcil, " +
                "uint32_t* version, uint8_t* type, uint8_t* scid, size_t* scid_len, " +
                "uint8_t* dcid, size_t* dcid_len, uint8_t* token, size_t* token_len)",
        )
    private val fnConfigLoadCertChainFromPemFile =
        quicheLibrary.func("int quiche_config_load_cert_chain_from_pem_file(void* config, const char* path)")
    private val fnConfigLoadPrivKeyFromPemFile =
        quicheLibrary.func("int quiche_config_load_priv_key_from_pem_file(void* config, const char* path)")

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
    ): QuicheConn {
        // The C signature takes a null-terminated server_name and no length param —
        // callers on every platform are responsible for null-termination. `serverNameLen`
        // is ignored here; it exists on the Kotlin interface to support future
        // length-aware platform variants.
        val raw =
            fnConnect(
                serverNameAddr.asPointer(),
                scidAddr.asPointer(),
                scidLen,
                localAddr.asPointer(),
                localAddrLen,
                peerAddr.asPointer(),
                peerAddrLen,
                config.handle.asPointer(),
            )
        return QuicheConn(addressOf(raw))
    }

    override fun connFree(conn: QuicheConn) {
        fnConnFree(conn.handle.asPointer())
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int =
        fnConnRecv(
            conn.handle.asPointer(),
            buf.asPointer(),
            bufLen,
            recvInfo.handle.asPointer(),
        ).unsafeCast<Int>()

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int =
        fnConnSend(
            conn.handle.asPointer(),
            buf.asPointer(),
            bufLen,
            sendInfo.handle.asPointer(),
        ).unsafeCast<Int>()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        // bool is 1 byte in the C ABI; Uint8Array(1) gives us the exact storage.
        val finBuf = js("new Uint8Array(1)")
        val errBuf = js("new BigInt64Array(1)")
        val n =
            fnConnStreamRecv(
                conn.handle.asPointer(),
                streamId.id.asPointer(),
                buf.asPointer(),
                bufLen,
                finBuf,
                errBuf,
            ).unsafeCast<Int>()
        return when {
            n >= 0 -> StreamRecvResult.Data(bytesRead = n, fin = finBuf[0].unsafeCast<Int>() != 0)
            n == QUICHE_ERR_DONE -> StreamRecvResult.Done
            else -> StreamRecvResult.Error(n)
        }
    }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int {
        val errBuf = js("new BigInt64Array(1)")
        return fnConnStreamSend(
            conn.handle.asPointer(),
            streamId.id.asPointer(),
            buf.asPointer(),
            bufLen,
            fin,
            errBuf,
        ).unsafeCast<Int>()
    }

    override fun connIsEstablished(conn: QuicheConn): Boolean = fnConnIsEstablished(conn.handle.asPointer()).unsafeCast<Boolean>()

    override fun connIsClosed(conn: QuicheConn): Boolean = fnConnIsClosed(conn.handle.asPointer()).unsafeCast<Boolean>()

    override fun connIsTimedOut(conn: QuicheConn): Boolean = fnConnIsTimedOut(conn.handle.asPointer()).unsafeCast<Boolean>()

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
    ): Int = fnConfigLoadCertChainFromPemFile(config.handle.asPointer(), pathAddr.asPointer()).unsafeCast<Int>()

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = fnConfigLoadPrivKeyFromPemFile(config.handle.asPointer(), pathAddr.asPointer()).unsafeCast<Int>()

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
        fnHeaderInfo(
            buf.asPointer(),
            bufLen,
            dcil,
            versionOut.asPointer(),
            typeOut.asPointer(),
            scidOut.asPointer(),
            scidLenOut.asPointer(),
            dcidOut.asPointer(),
            dcidLenOut.asPointer(),
            tokenOut.asPointer(),
            tokenLenOut.asPointer(),
        ).unsafeCast<Int>()

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
        val raw =
            fnAccept(
                scidAddr.asPointer(),
                scidLen,
                odcidAddr.asPointer(),
                odcidLen,
                localAddr.asPointer(),
                localAddrLen,
                peerAddr.asPointer(),
                peerAddrLen,
                config.handle.asPointer(),
            )
        return QuicheConn(addressOf(raw))
    }

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int =
        fnNegotiateVersion(
            scidAddr.asPointer(),
            scidLen,
            dcidAddr.asPointer(),
            dcidLen,
            outAddr.asPointer(),
            outLen,
        ).unsafeCast<Int>()

    // Stream iteration
    override fun connReadable(conn: QuicheConn): QuicheStreamIter = QuicheStreamIter(addressOf(fnConnReadable(conn.handle.asPointer())))

    override fun connWritable(conn: QuicheConn): QuicheStreamIter = QuicheStreamIter(addressOf(fnConnWritable(conn.handle.asPointer())))

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? {
        // C signature: bool quiche_stream_iter_next(quiche_stream_iter *iter, uint64_t *stream_id).
        // koffi accepts a TypedArray as a raw buffer pointer — BigInt64Array(1) is exactly 8 bytes
        // of backing memory, matching a uint64_t slot. koffi writes the stream id into [0] before
        // returning.
        val outBuf = js("new BigInt64Array(1)")
        val hasNext = fnStreamIterNext(iter.handle.asPointer(), outBuf).unsafeCast<Boolean>()
        if (!hasNext) return null
        val streamId = outBuf[0].toString().unsafeCast<String>().toLong()
        return QuicStreamId(streamId)
    }

    override fun streamIterFree(iter: QuicheStreamIter) {
        fnStreamIterFree(iter.handle.asPointer())
    }

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
