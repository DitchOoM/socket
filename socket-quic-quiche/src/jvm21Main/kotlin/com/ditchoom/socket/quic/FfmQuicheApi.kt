@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.socket.quic

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
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
    /** Witness that [FfmLibrary] is open, so a packaging failure surfaces at load and not at the first call. */
    private val library: FfmLibrary,
) : QuicheApi {
    // Pointers cross the downcall boundary as plain `long`s: every FunctionDescriptor in `FfmDowncalls.kt` declares an
    // opaque `T *` as JAVA_LONG, which is the same register on every 64-bit ABI this tier runs on (SysV
    // x86-64, AAPCS64 incl. Apple arm64, Windows x64; the tier already hard-codes 64-bit struct layouts
    // and a 64-bit `size_t`). An ADDRESS parameter would need a MemorySegment per argument, i.e.
    // `MemorySegment.ofAddress` (a NativeMemorySegmentImpl plus its session) on every quiche call.

    /**
     * NUL-terminated UTF-8 copy of [s], allocated in [arena] — a `const char *` for quiche.
     *
     * Hand-rolled instead of the one-call `Arena` helper because that helper has two names and this
     * class has to satisfy both. It was `allocateUtf8String` in JDK 21's PREVIEW FFM API and was
     * renamed to `allocateFrom` when JEP 454 finalised FFM in JDK 22. This tier is compiled by
     * `jvmToolchain(21)` but selected at runtime by every JDK >= 21 through `META-INF/versions/21`,
     * so either name is wrong for half the supported range: `allocateUtf8String` compiles here and
     * throws `NoSuchMethodError` on JDK 22+, and `allocateFrom` does not exist on the
     * JDK 21 that compiles it. `allocate` + `MemorySegment.copy` is spelled identically in both.
     *
     * Do not "simplify" this back to a single call without moving the whole `java21` compilation to a
     * JDK 22+ toolchain — the failure is invisible at build time and only reaches a consumer.
     */
    private fun cString(
        arena: Arena,
        s: String,
    ): MemorySegment {
        // Platform boundary: MemorySegment.copy's array overload is the only spelling of
        // String -> native UTF-8 that exists unchanged on both JDK 21 and JDK 22+.
        @Suppress("NoByteArrayInProd")
        val bytes = s.encodeToByteArray()
        // +1 for the terminator. FFM zero-fills a fresh allocation, so the trailing byte is already
        // NUL; it is written explicitly so the invariant does not depend on that guarantee.
        val segment = arena.allocate(bytes.size + 1L)
        MemorySegment.copy(bytes, 0, segment, JAVA_BYTE, 0L, bytes.size)
        segment.set(JAVA_BYTE, bytes.size.toLong(), 0)
        return segment
    }

    // --- Path migration handles ---

    // --- Config ---
    override fun configNew(version: Int): QuicheConfig = QuicheConfig(ConfigCalls.hConfigNew.invokeExact(version) as Long)

    override fun configFree(config: QuicheConfig) {
        ConfigCalls.hConfigFree.invokeExact(config.handle)
    }

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = ConfigCalls.hConfigSetAppProtos.invokeExact(config.handle, protosAddr, protosLen.toLong()) as Int

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) {
        ConfigCalls.hConfigSetMaxIdleTimeout.invokeExact(config.handle, timeout)
    }

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        ConfigCalls.hConfigSetMaxRecvUdp.invokeExact(config.handle, size)
    }

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        ConfigCalls.hConfigSetMaxSendUdp.invokeExact(config.handle, size)
    }

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetMaxData.invokeExact(config.handle, v)
    }

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetBidiLocal.invokeExact(config.handle, v)
    }

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetBidiRemote.invokeExact(config.handle, v)
    }

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetUni.invokeExact(config.handle, v)
    }

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetStreamsBidi.invokeExact(config.handle, v)
    }

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetStreamsUni.invokeExact(config.handle, v)
    }

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) {
        ConfigCalls.hDisableMigration.invokeExact(config.handle, v)
    }

    override fun configSetActiveConnectionIdLimit(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetActiveConnectionIdLimit.invokeExact(config.handle, v)
    }

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) {
        ConfigCalls.hVerifyPeer.invokeExact(config.handle, v)
    }

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) {
        ConfigCalls.hEnablePacing.invokeExact(config.handle, v)
    }

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetMaxPacingRate.invokeExact(config.handle, v)
    }

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) {
        ConfigCalls.hSetCcAlgorithm.invokeExact(config.handle, algo)
    }

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) {
        ConfigCalls.hEnableHystart.invokeExact(config.handle, v)
    }

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) {
        ConfigCalls.hSetInitialCwndPackets.invokeExact(config.handle, packets)
    }

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetMaxConnWindow.invokeExact(config.handle, v)
    }

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) {
        ConfigCalls.hSetMaxStreamWindow.invokeExact(config.handle, v)
    }

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) {
        ConfigCalls.hDiscoverPmtu.invokeExact(config.handle, v)
    }

    override fun configEnableEarlyData(config: QuicheConfig) {
        ConfigCalls.hEnableEarlyData.invokeExact(config.handle)
    }

    override fun configSetTicketKey(
        config: QuicheConfig,
        keyAddr: Long,
        keyLen: Int,
    ): Int = ConfigCalls.hSetTicketKey.invokeExact(config.handle, keyAddr, keyLen.toLong()) as Int

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) {
        ConfigCalls.hGrease.invokeExact(config.handle, v)
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
            ConnCalls.hConnect.invokeExact(
                serverNameAddr,
                scidAddr,
                scidLen.toLong(),
                localAddr,
                localAddrLen,
                peerAddr,
                peerAddrLen,
                config.handle,
            ) as Long
        return QuicheConn(result)
    }

    override fun connFree(conn: QuicheConn) {
        ConnCalls.hConnFree.invokeExact(conn.handle)
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = (HotCalls.hConnRecv.invokeExact(conn.handle, buf, bufLen.toLong(), recvInfo.handle) as Long).toInt()

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = (HotCalls.hConnSend.invokeExact(conn.handle, buf, bufLen.toLong(), sendInfo.handle) as Long).toInt()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        val out = outParams.get()
        val raw =
            HotCalls.hStreamRecv.invokeExact(
                conn.handle,
                streamId.id,
                buf,
                bufLen.toLong(),
                out.fin.address(),
                out.errorCode.address(),
            ) as Long
        return if (raw >= 0) {
            val fin = out.fin.get(JAVA_BOOLEAN, 0)
            StreamRecvResult.Data(raw.toInt(), fin)
        } else if (raw == QUICHE_ERR_DONE) {
            StreamRecvResult.Done
        } else if (raw.toInt() == QUICHE_ERR_STREAM_RESET) {
            StreamRecvResult.Reset(QuicAppErrorCode(out.errorCode.get(JAVA_LONG, 0)))
        } else {
            StreamRecvResult.Error(raw.toInt())
        }
    }

    override fun connStreamRecvStep(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvStep {
        val out = outParams.get()
        val raw =
            HotCalls.hStreamRecv.invokeExact(
                conn.handle,
                streamId.id,
                buf,
                bufLen.toLong(),
                out.fin.address(),
                out.errorCode.address(),
            ) as Long
        return if (raw >= 0) {
            StreamRecvStep.data(raw.toInt(), out.fin.get(JAVA_BOOLEAN, 0))
        } else if (raw.toInt() == QUICHE_ERR_STREAM_RESET) {
            StreamRecvStep.reset(QuicAppErrorCode(out.errorCode.get(JAVA_LONG, 0)))
        } else {
            StreamRecvStep.error(raw.toInt())
        }
    }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult {
        val errOut = outParams.get().errorCode
        val result =
            (
                HotCalls.hStreamSend.invokeExact(
                    conn.handle,
                    streamId.id,
                    buf,
                    bufLen.toLong(),
                    fin,
                    errOut.address(),
                ) as Long
            ).toInt()
        // quiche fills out_error_code only on STREAM_STOPPED / STREAM_RESET, and it is read only then.
        val errorCode =
            if (result == QUICHE_ERR_STREAM_STOPPED || result == QUICHE_ERR_STREAM_RESET) {
                QuicAppErrorCode(errOut.get(JAVA_LONG, 0))
            } else {
                null
            }
        return StreamSendResult(result, errorCode)
    }

    override fun connStreamSendStep(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendStep {
        val errOut = outParams.get().errorCode
        val result =
            (
                HotCalls.hStreamSend.invokeExact(
                    conn.handle,
                    streamId.id,
                    buf,
                    bufLen.toLong(),
                    fin,
                    errOut.address(),
                ) as Long
            ).toInt()
        return StreamSendStep.of(result) { QuicAppErrorCode(errOut.get(JAVA_LONG, 0)) }
    }

    /**
     * The out-parameters of the per-packet and per-stream calls ([connStreamRecv], [connStreamSend],
     * [connPathStats], [copyConnBytes] and the connection-id iterators), one set per thread, allocated once: the same reasoning as [streamIdScratch]. A
     * call writes them and reads them back on its own thread with nothing in between that can suspend,
     * so no other call can overwrite them first. They were a confined arena per call, created and
     * closed on every stream read, stream write and flush.
     *
     * Each value is read only where quiche has written it: `fin` on a successful read, `errorCode` on
     * STREAM_RESET / STREAM_STOPPED, and the path-stats struct on success. Nothing relies on the zero
     * fill a fresh arena gave.
     */
    private class OutParams {
        private val arena = Arena.ofAuto()
        val fin: MemorySegment = arena.allocate(JAVA_BOOLEAN)
        val errorCode: MemorySegment = arena.allocate(JAVA_LONG)
        val pathStats: MemorySegment = arena.allocate(PATH_STATS_SIZE, 8)
        val connStats: MemorySegment = arena.allocate(CONN_STATS_SIZE, 8)

        /** `const uint8_t **out` and `size_t *out_len` of quiche's byte-slice readers. */
        val bytePtr: MemorySegment = arena.allocate(JAVA_LONG)
        val byteLen: MemorySegment = arena.allocate(JAVA_LONG)
    }

    private val outParams: ThreadLocal<OutParams> = ThreadLocal.withInitial(::OutParams)

    override fun connStreamCapacity(
        conn: QuicheConn,
        streamId: QuicStreamId,
    ): Long = HotCalls.hStreamCapacity.invokeExact(conn.handle, streamId.id) as Long

    override fun connPeerStreamsLeftBidi(conn: QuicheConn): Long = HotCalls.hPeerStreamsLeftBidi.invokeExact(conn.handle) as Long

    override fun connPeerStreamsLeftUni(conn: QuicheConn): Long = HotCalls.hPeerStreamsLeftUni.invokeExact(conn.handle) as Long

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int = HotCalls.hStreamShutdown.invokeExact(conn.handle, streamId.id, direction, err) as Int

    override fun connPeerCert(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int =
        Arena.ofConfined().use { arena ->
            val outPtr = arena.allocate(JAVA_LONG) // const uint8_t **out
            val outLen = arena.allocate(JAVA_LONG) // size_t *out_len
            ConnCalls.hConnPeerCert.invokeExact(conn.handle, outPtr.address(), outLen.address())
            val len = outLen.get(JAVA_LONG, 0).toInt()
            if (len <= 0) return@use 0
            // Copy out only when it fits; otherwise report the needed length so the caller retries.
            if (len <= bufLen) {
                MemorySegment.copy(NATIVE, outPtr.get(JAVA_LONG, 0), NATIVE, buf, len.toLong())
            }
            len
        }

    override fun connPeerCertChainAt(
        conn: QuicheConn,
        index: Int,
        buf: Long,
        bufLen: Int,
    ): Int {
        if (index < 0) return 0
        return Arena.ofConfined().use { arena ->
            val outPtr = arena.allocate(JAVA_LONG) // const uint8_t **out
            val outLen = arena.allocate(JAVA_LONG) // size_t *out_len
            ConnCalls.hConnPeerCertChainAt.invokeExact(conn.handle, index.toLong(), outPtr.address(), outLen.address())
            val len = outLen.get(JAVA_LONG, 0).toInt()
            if (len <= 0) return@use 0
            // Same snprintf-style contract as connPeerCert: copy only when it fits.
            if (len <= bufLen) {
                MemorySegment.copy(NATIVE, outPtr.get(JAVA_LONG, 0), NATIVE, buf, len.toLong())
            }
            len
        }
    }

    override fun connApplicationProto(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int =
        Arena.ofConfined().use { arena ->
            val outPtr = arena.allocate(JAVA_LONG) // const uint8_t **out
            val outLen = arena.allocate(JAVA_LONG) // size_t *out_len
            ConnCalls.hConnApplicationProto.invokeExact(conn.handle, outPtr.address(), outLen.address())
            val len = outLen.get(JAVA_LONG, 0).toInt()
            if (len <= 0) return@use 0
            // Same snprintf-style contract as connPeerCert: copy only when it fits.
            if (len <= bufLen) {
                MemorySegment.copy(NATIVE, outPtr.get(JAVA_LONG, 0), NATIVE, buf, len.toLong())
            }
            len
        }

    override fun connTraceId(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = copyConnBytes(ConnCalls.hConnTraceId, conn, buf, bufLen)

    override fun connSourceId(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = copyConnBytes(ConnCalls.hConnSourceId, conn, buf, bufLen)

    /**
     * Shared body for quiche's `(const uint8_t **out, size_t *out_len)` readers, honouring the
     * snprintf-style contract: copy only when it fits, always return the true length. Factored out
     * because [connTraceId] and [connSourceId] differ only in which downcall handle they invoke.
     */
    private fun copyConnBytes(
        handle: java.lang.invoke.MethodHandle,
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        val out = outParams.get()
        handle.invokeExact(conn.handle, out.bytePtr.address(), out.byteLen.address())
        val len = out.byteLen.get(JAVA_LONG, 0).toInt()
        if (len <= 0) return 0
        if (len <= bufLen) {
            MemorySegment.copy(NATIVE, out.bytePtr.get(JAVA_LONG, 0), NATIVE, buf, len.toLong())
        }
        return len
    }

    // quiche_stats layout (64-bit; size_t == uint64_t == 8 bytes, so it is 25 consecutive 8-byte
    // fields then a trailing bool — same hand-offset discipline as the send_info constants below):
    //   [0]   recv                [8]   sent                [16]  lost
    //   [24]  spurious_lost       [32]  retrans             [40]  sent_bytes
    //   [48]  recv_bytes          [56]  acked_bytes         [64]  lost_bytes
    //   [72]  stream_retrans_bytes[80]  dgram_recv          [88]  dgram_sent
    //   [96]  paths_count         [104..192] reset/stopped/blocked counters (unbound — see
    //   QuicConnStats KDoc)      [200] tx_buffered_inconsistent (bool) → total 208
    override fun connStats(conn: QuicheConn): QuicConnStats? =
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(CONN_STATS_SIZE, 8)
            HotCalls.hConnStats.invokeExact(conn.handle, out.address())
            QuicConnStats(
                recv = out.get(JAVA_LONG, 0),
                sent = out.get(JAVA_LONG, 8),
                lost = out.get(JAVA_LONG, 16),
                spuriousLost = out.get(JAVA_LONG, 24),
                retrans = out.get(JAVA_LONG, 32),
                sentBytes = out.get(JAVA_LONG, 40),
                recvBytes = out.get(JAVA_LONG, 48),
                ackedBytes = out.get(JAVA_LONG, 56),
                lostBytes = out.get(JAVA_LONG, 64),
                streamRetransBytes = out.get(JAVA_LONG, 72),
                dgramRecv = out.get(JAVA_LONG, 80),
                dgramSent = out.get(JAVA_LONG, 88),
                pathsCount = out.get(JAVA_LONG, 96),
            )
        }

    override fun connLossEvidence(conn: QuicheConn): Long {
        val out = outParams.get().connStats
        HotCalls.hConnStats.invokeExact(conn.handle, out.address())
        return out.get(JAVA_LONG, 16) + out.get(JAVA_LONG, 32) // lost + retrans, offsets as in connStats
    }

    /**
     * `quiche_conn_peer_transport_params` returns false until the handshake has processed the peer's
     * parameters — [PeerTransportParams.NotYetNegotiated], a timing state that resolves on its own.
     *
     * `Arena.allocate` already zeroes, so unlike the K/N backends there is nothing to `memset` here.
     */
    override fun connPeerTransportParams(conn: QuicheConn): PeerTransportParams =
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(TRANSPORT_PARAMS_SIZE, 8)
            val known = ConnCalls.hConnPeerTransportParams.invokeExact(conn.handle, out.address()) as Boolean
            if (!known) return@use PeerTransportParams.NotYetNegotiated
            PeerTransportParams.Negotiated(
                maxIdleTimeoutMillis = out.get(JAVA_LONG, 0),
                maxUdpPayloadSize = out.get(JAVA_LONG, 8),
                initialMaxData = out.get(JAVA_LONG, 16),
                initialMaxStreamDataBidiLocal = out.get(JAVA_LONG, 24),
                initialMaxStreamDataBidiRemote = out.get(JAVA_LONG, 32),
                initialMaxStreamDataUni = out.get(JAVA_LONG, 40),
                initialMaxStreamsBidi = out.get(JAVA_LONG, 48),
                initialMaxStreamsUni = out.get(JAVA_LONG, 56),
                ackDelayExponent = out.get(JAVA_LONG, 64),
                maxAckDelayMillis = out.get(JAVA_LONG, 72),
                disableActiveMigration = out.get(JAVA_BOOLEAN, TRANSPORT_PARAMS_DISABLE_ACTIVE_MIGRATION_OFFSET),
                activeConnIdLimit = out.get(JAVA_LONG, 88),
                maxDatagramFrameSize = out.get(JAVA_LONG, 96),
            )
        }

    // quiche_path_stats layout (64-bit; sockaddr_storage is 128 bytes/8-aligned — exactly the
    // quiche_send_info layout discipline: from@0, from_len@128, pad to 8):
    //   [0]   local_addr (128)    [128] local_addr_len (4) + pad
    //   [136] peer_addr (128)     [264] peer_addr_len (4) + pad
    //   [272] validation_state    [280] active (bool) + pad
    //   [288] recv   [296] sent   [304] lost   [312] retrans   [320] total_pto_count
    //   [328] dgram_recv (unbound) [336] dgram_sent (unbound)
    //   [344] rtt    [352] min_rtt [360] max_rtt [368] rttvar
    //   [376] cwnd   [384] sent_bytes [392] recv_bytes [400] lost_bytes
    //   [408] stream_retrans_bytes [416] pmtu [424] delivery_rate
    //   [432] max_bandwidth (unbound) [440] startup_exit_cwnd (unbound) → total 448
    override fun connPathStats(
        conn: QuicheConn,
        pathIdx: Long,
    ): QuicPathStats? {
        val out = outParams.get().pathStats
        val rc = HotCalls.hConnPathStats.invokeExact(conn.handle, pathIdx, out.address()) as Int
        if (rc < 0) return null
        return QuicPathStats(
            validationState = out.get(JAVA_LONG, 272),
            active = out.get(JAVA_BOOLEAN, 280),
            recv = out.get(JAVA_LONG, 288),
            sent = out.get(JAVA_LONG, 296),
            lost = out.get(JAVA_LONG, 304),
            retrans = out.get(JAVA_LONG, 312),
            totalPtoCount = out.get(JAVA_LONG, 320),
            rtt = out.get(JAVA_LONG, 344).nanoseconds,
            minRtt = out.get(JAVA_LONG, 352).nanoseconds,
            maxRtt = out.get(JAVA_LONG, 360).nanoseconds,
            rttvar = out.get(JAVA_LONG, 368).nanoseconds,
            cwnd = out.get(JAVA_LONG, 376),
            sentBytes = out.get(JAVA_LONG, 384),
            recvBytes = out.get(JAVA_LONG, 392),
            lostBytes = out.get(JAVA_LONG, 400),
            streamRetransBytes = out.get(JAVA_LONG, 408),
            pmtu = out.get(JAVA_LONG, 416),
            deliveryRate = out.get(JAVA_LONG, 424),
        )
    }

    override fun connPeerError(conn: QuicheConn): QuicError? = readConnError(ConnCalls.hConnPeerError, conn)

    override fun connLocalError(conn: QuicheConn): QuicError? = readConnError(ConnCalls.hConnLocalError, conn)

    /**
     * Shared decode for `quiche_conn_{peer,local}_error`: returns the typed [QuicError] when the C call
     * reports a close (true), else null. The reason-phrase out-params are bound but ignored — the close
     * reason is the sealed [QuicError], not a free-form string (RFC 9000 §19.19 reason phrases are
     * advisory). `is_app` selects the application vs transport mapping.
     */
    private fun readConnError(
        handle: MethodHandle,
        conn: QuicheConn,
    ): QuicError? =
        Arena.ofConfined().use { arena ->
            val isApp = arena.allocate(JAVA_BOOLEAN)
            val codeOut = arena.allocate(JAVA_LONG)
            val reasonPtr = arena.allocate(JAVA_LONG) // const uint8_t **reason
            val reasonLen = arena.allocate(JAVA_LONG)
            val present =
                handle.invokeExact(
                    conn.handle,
                    isApp.address(),
                    codeOut.address(),
                    reasonPtr.address(),
                    reasonLen.address(),
                ) as Boolean
            if (!present) return@use null
            val code = codeOut.get(JAVA_LONG, 0)
            if (isApp.get(JAVA_BOOLEAN, 0)) QuicError.ApplicationError(code) else QuicError.fromTransportCode(code)
        }

    // --- Unreliable datagrams (RFC 9221) ---

    override fun configEnableDgram(
        config: QuicheConfig,
        enabled: Boolean,
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) {
        ConfigCalls.hConfigEnableDgram.invokeExact(config.handle, enabled, recvQueueLen, sendQueueLen)
    }

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = (HotCalls.hConnDgramSend.invokeExact(conn.handle, buf, bufLen.toLong()) as Long).toInt()

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        // ssize_t: >= 0 length (datagrams have no FIN), QUICHE_ERR_DONE when none queued, else error.
        val raw = HotCalls.hConnDgramRecv.invokeExact(conn.handle, buf, bufLen.toLong()) as Long
        return when {
            raw >= 0 -> StreamRecvResult.Data(raw.toInt(), false)
            raw == QUICHE_ERR_DONE -> StreamRecvResult.Done
            else -> StreamRecvResult.Error(raw.toInt())
        }
    }

    override fun connDgramRecvStep(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvStep {
        val raw = HotCalls.hConnDgramRecv.invokeExact(conn.handle, buf, bufLen.toLong()) as Long
        return if (raw >= 0) StreamRecvStep.data(raw.toInt(), false) else StreamRecvStep.error(raw.toInt())
    }

    override fun connDgramMaxWritableBytes(conn: QuicheConn): Int {
        val raw = HotCalls.hConnDgramMaxWritableLen.invokeExact(conn.handle) as Long
        return if (raw < 0) NO_DATAGRAM_ROOM else raw.toInt()
    }

    override fun connDgramPurgeOutgoing(conn: QuicheConn): Int {
        val held = ConnCalls.hConnDgramSendQueueLen.invokeExact(conn.handle) as Long
        ConnCalls.hConnDgramPurgeOutgoing.invokeExact(conn.handle, ConnCalls.purgeEveryDgram)
        return held.toInt()
    }

    override fun connDgramSendQueueLen(conn: QuicheConn): Int = (ConnCalls.hConnDgramSendQueueLen.invokeExact(conn.handle) as Long).toInt()

    override fun connDgramRecvQueueLen(conn: QuicheConn): Int = (HotCalls.hConnDgramRecvQueueLen.invokeExact(conn.handle) as Long).toInt()

    override fun hasReadableDgram(conn: QuicheConn): Boolean = (HotCalls.hConnDgramRecvFrontLen.invokeExact(conn.handle) as Long) >= 0

    override fun connDgramMaxWritableLen(conn: QuicheConn): MaxDatagramSize {
        val raw = HotCalls.hConnDgramMaxWritableLen.invokeExact(conn.handle) as Long
        return if (raw < 0) MaxDatagramSize.Unavailable else MaxDatagramSize.Bytes(raw.toInt())
    }

    override fun connIsEstablished(conn: QuicheConn): Boolean = HotCalls.hIsEstablished.invokeExact(conn.handle) as Boolean

    override fun connIsHandshakeConfirmed(conn: QuicheConn): Boolean = HotCalls.hIsHandshakeConfirmed.invokeExact(conn.handle) as Boolean

    override fun connIsClosed(conn: QuicheConn): Boolean = HotCalls.hIsClosed.invokeExact(conn.handle) as Boolean

    override fun connIsDraining(conn: QuicheConn): Boolean = HotCalls.hIsDraining.invokeExact(conn.handle) as Boolean

    override fun connIsTimedOut(conn: QuicheConn): Boolean = HotCalls.hIsTimedOut.invokeExact(conn.handle) as Boolean

    override fun connSetSession(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = ConnCalls.hConnSetSession.invokeExact(conn.handle, buf, bufLen.toLong()) as Int

    override fun connSession(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = copyConnBytes(ConnCalls.hConnSession, conn, buf, bufLen)

    override fun connSetToken(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = ConnCalls.hSetToken.invokeExact(conn.handle, buf, bufLen.toLong()) as Int

    override fun connTakeNewToken(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = (ConnCalls.hTakeNewToken.invokeExact(conn.handle, buf, bufLen.toLong()) as Long).toInt()

    override fun connSendNewToken(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = ConnCalls.hSendNewToken.invokeExact(conn.handle, buf, bufLen.toLong()) as Int

    override fun connSetPeerAddressValidated(conn: QuicheConn): Int = ConnCalls.hSetPeerAddressValidated.invokeExact(conn.handle) as Int

    override fun connIsResumed(conn: QuicheConn): Boolean = ConnCalls.hIsResumed.invokeExact(conn.handle) as Boolean

    override fun connIsInEarlyData(conn: QuicheConn): Boolean = ConnCalls.hIsInEarlyData.invokeExact(conn.handle) as Boolean

    override fun connEarlyDataReason(conn: QuicheConn): Int = ConnCalls.hEarlyDataReason.invokeExact(conn.handle) as Int

    override fun connResetStreamsOnEarlyDataReject(conn: QuicheConn) {
        ConnCalls.hResetStreamsOnEarlyDataReject.invokeExact(conn.handle)
    }

    override fun connStreamDataUnacknowledged(conn: QuicheConn): Boolean =
        HotCalls.hStreamDataUnacknowledged.invokeExact(conn.handle) as Boolean

    override fun connRecvGaps(conn: QuicheConn): Long = HotCalls.hRecvGaps.invokeExact(conn.handle) as Long

    override fun connTimeout(conn: QuicheConn): Duration? {
        val nanos = HotCalls.hTimeoutNanos.invokeExact(conn.handle) as Long
        return if (nanos < 0) null else nanos.nanoseconds
    }

    override fun connTimeoutNanos(conn: QuicheConn): Long {
        val nanos = HotCalls.hTimeoutNanos.invokeExact(conn.handle) as Long
        return if (nanos < 0) NO_TIMEOUT_NANOS else nanos
    }

    override fun connOnTimeout(conn: QuicheConn) {
        HotCalls.hOnTimeout.invokeExact(conn.handle)
    }

    override fun setThreadVirtualTimeNanos(nanos: Long) {
        SimulationCalls.hSetVirtualTimeNanos.invokeExact(nanos)
    }

    override fun clearThreadVirtualTime() {
        SimulationCalls.hClearVirtualTime.invokeExact()
    }

    /** This backend's caller-entropy binding. Internal: deterministic simulation only — see [QuicheEntropy]. */
    internal val randomPin: QuicheRandomPin =
        object : QuicheRandomPin {
            override fun setThreadRandomState(state: Long) {
                SimulationCalls.hSetThreadRandomState.invokeExact(state)
            }

            override fun clearThreadRandomState() {
                SimulationCalls.hClearThreadRandomState.invokeExact()
            }
        }

    /** This backend's clock pin: a loop pins libquiche to the instant it read for its pass. */
    internal val clockPin: QuicheClockPin =
        object : QuicheClockPin {
            override fun pinMonotonic(nanos: Long): PinAnswer =
                if (SimulationCalls.hPinMonotonicTimeNanos.invokeExact(nanos) as Int == PINNED) PinAnswer.Pinned else PinAnswer.Unsupported

            override fun unpin() {
                SimulationCalls.hClearVirtualTime.invokeExact()
            }
        }

    init {
        QuicheClockPins.bind(this, clockPin)
    }

    override fun connSendAckEliciting(conn: QuicheConn): Int = (HotCalls.hSendAckEliciting.invokeExact(conn.handle) as Long).toInt()

    override fun connProbeNow(conn: QuicheConn): Int = (HotCalls.hProbeNow.invokeExact(conn.handle) as Long).toInt()

    override fun connRequeuePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): Int = (PathCalls.hRequeuePath.invokeExact(conn.handle, localAddr, localLen, peerAddr, peerLen) as Long).toInt()

    override fun configSetInitialRtt(
        config: QuicheConfig,
        nanos: Long,
    ) {
        ConfigCalls.hSetInitialRtt.invokeExact(config.handle, nanos)
    }

    override fun connSetHandshakePtoCeiling(
        conn: QuicheConn,
        ceiling: PtoCeiling,
    ) {
        ConnCalls.hSetHandshakePtoCeiling.invokeExact(
            conn.handle,
            ptoCeilingFirstNanos(ceiling),
            ptoCeilingGrowth(ceiling),
            ptoCeilingMaxNanos(ceiling),
        )
    }

    override fun connHandshakePtoCeilingHeld(conn: QuicheConn): Boolean =
        HotCalls.hHandshakePtoCeilingHeld.invokeExact(conn.handle) as Boolean

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int =
        ConnCalls.hConnClose.invokeExact(
            conn.handle,
            error is QuicError.ApplicationError,
            error.code,
            0L,
            0L,
        ) as Int

    // --- Path migration ---
    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): ProbeOutcome =
        Arena.ofConfined().use { arena ->
            val seqOut = arena.allocate(JAVA_LONG)
            val rc =
                PathCalls.hConnProbePath.invokeExact(
                    conn.handle,
                    localAddr,
                    localLen,
                    peerAddr,
                    peerLen,
                    seqOut.address(),
                ) as Int
            if (rc >= 0) ProbeOutcome.Probed(seqOut.get(JAVA_LONG, 0)) else ProbeOutcome.Rejected(rc)
        }

    override fun connProbePathWith(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        initialRttNanos: Long,
    ): ProbeOutcome =
        Arena.ofConfined().use { arena ->
            val seqOut = arena.allocate(JAVA_LONG)
            val rc =
                PathCalls.hConnProbePathWith.invokeExact(
                    conn.handle,
                    localAddr,
                    localLen,
                    peerAddr,
                    peerLen,
                    initialRttNanos,
                    seqOut.address(),
                ) as Int
            if (rc >= 0) ProbeOutcome.Probed(seqOut.get(JAVA_LONG, 0)) else ProbeOutcome.Rejected(rc)
        }

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int =
        PathCalls.hConnNewScid.invokeExact(
            conn.handle,
            scidAddr,
            scidLen.toLong(),
            resetTokenAddr,
            retireIfNeeded,
            seqOut,
        ) as Int

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): MigrateOutcome =
        Arena.ofConfined().use { arena ->
            val seqOut = arena.allocate(JAVA_LONG)
            val rc =
                PathCalls.hConnMigrate.invokeExact(
                    conn.handle,
                    localAddr,
                    localLen,
                    peerAddr,
                    peerLen,
                    seqOut.address(),
                ) as Int
            if (rc >= 0) MigrateOutcome.Migrated(seqOut.get(JAVA_LONG, 0)) else MigrateOutcome.Rejected(rc)
        }

    override fun connRetireDcid(
        conn: QuicheConn,
        dcidSeq: Long,
    ): Int = PathCalls.hConnRetireDcid.invokeExact(conn.handle, dcidSeq) as Int

    override fun connRetiredScids(conn: QuicheConn): Int = (PathCalls.hConnRetiredScids.invokeExact(conn.handle) as Long).toInt()

    override fun connDrainRetiredScids(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int {
        val iter = PathCalls.hConnRetiredScidIter.invokeExact(conn.handle) as Long
        // A null iterator is "nothing retired", not a failure — same shape as connPathEventNext's null.
        if (iter == 0L) return 0
        val scratch = outParams.get()
        val outPtr = scratch.bytePtr
        val outLen = scratch.byteLen
        return run {
            var yielded = 0
            try {
                // Runs to completion even once the buffer is full: the iterator has already drained
                // quiche's list, so stopping early would free ids nothing has recorded.
                while (PathCalls.hConnIdIterNext.invokeExact(iter, outPtr.address(), outLen.address()) as Boolean) {
                    val len = outLen.get(JAVA_LONG, 0).toInt()
                    if (yielded < maxIds && len in 1..QUIC_MAX_CONN_ID_LEN) {
                        val slot = out + yielded.toLong() * RETIRED_SCID_SLOT_BYTES
                        NATIVE.set(JAVA_BYTE, slot, len.toByte())
                        // Same copy as connApplicationProto: quiche hands back a pointer into memory the
                        // iterator owns, so it must be copied before the free below.
                        MemorySegment.copy(NATIVE, outPtr.get(JAVA_LONG, 0), NATIVE, slot + 1L, len.toLong())
                    }
                    yielded++
                }
            } finally {
                PathCalls.hConnIdIterFree.invokeExact(iter)
            }
            yielded
        }
    }

    override fun connActiveScids(conn: QuicheConn): Int = (PathCalls.hConnActiveScids.invokeExact(conn.handle) as Long).toInt()

    override fun connReadSourceIds(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int {
        val iter = PathCalls.hConnSourceIds.invokeExact(conn.handle) as Long
        if (iter == 0L) return 0
        val scratch = outParams.get()
        val outPtr = scratch.bytePtr
        val outLen = scratch.byteLen
        return run {
            var yielded = 0
            try {
                // Runs to completion even once the buffer is full — not because ids would be lost
                // (quiche_conn_source_ids does not drain) but so the return value reports the true
                // total, which is what makes an undersized buffer inspectable rather than silent.
                while (PathCalls.hConnIdIterNext.invokeExact(iter, outPtr.address(), outLen.address()) as Boolean) {
                    val len = outLen.get(JAVA_LONG, 0).toInt()
                    if (yielded < maxIds && len in 1..QUIC_MAX_CONN_ID_LEN) {
                        val slot = out + yielded.toLong() * RETIRED_SCID_SLOT_BYTES
                        NATIVE.set(JAVA_BYTE, slot, len.toByte())
                        MemorySegment.copy(NATIVE, outPtr.get(JAVA_LONG, 0), NATIVE, slot + 1L, len.toLong())
                    }
                    yielded++
                }
            } finally {
                PathCalls.hConnIdIterFree.invokeExact(iter)
            }
            yielded
        }
    }

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int =
        PathCalls.hConnMigrateSource.invokeExact(
            conn.handle,
            localAddr,
            localLen,
            seqOut,
        ) as Int

    override fun connAvailableDcids(conn: QuicheConn): Long = PathCalls.hConnAvailableDcids.invokeExact(conn.handle) as Long

    override fun connScidsLeft(conn: QuicheConn): Long = PathCalls.hConnScidsLeft.invokeExact(conn.handle) as Long

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        val ev = PathCalls.hConnPathEventNext.invokeExact(conn.handle) as Long
        if (ev == 0L) return null
        val type = PathCalls.hPathEventType.invokeExact(ev) as Int
        when (type) {
            0 -> PathCalls.hPathEventNew.invokeExact(ev, localOut, localLenOut, peerOut, peerLenOut)
            1 -> PathCalls.hPathEventValidated.invokeExact(ev, localOut, localLenOut, peerOut, peerLenOut)
            2 -> PathCalls.hPathEventFailedValidation.invokeExact(ev, localOut, localLenOut, peerOut, peerLenOut)
            3 -> PathCalls.hPathEventClosed.invokeExact(ev, localOut, localLenOut, peerOut, peerLenOut)
            4 -> {
                // ReusedSourceConnectionId: extra old/new-tuple + CID-seq fields out of scope; surface no addresses.
                NATIVE.set(JAVA_INT, localLenOut, 0)
                NATIVE.set(JAVA_INT, peerLenOut, 0)
            }
            5 -> PathCalls.hPathEventPeerMigrated.invokeExact(ev, localOut, localLenOut, peerOut, peerLenOut)
            6 ->
                Arena.ofConfined().use { arena ->
                    // PmtuUpdated: the new PMTU is not surfaced; quiche already sizes its own sends by it.
                    val pmtuOut = arena.allocate(JAVA_LONG) // size_t *pmtu
                    // A statement, not the lambda's value: a void downcall must not be invoked as returning Object.
                    PathCalls.hPathEventPmtuUpdated.invokeExact(ev, localOut, localLenOut, peerOut, peerLenOut, pmtuOut.address())
                    Unit
                }
        }
        PathCalls.hPathEventFree.invokeExact(ev)
        return QuichePathEventType.entries[type]
    }

    // --- Server-side ---

    override fun connSetQlogPath(
        conn: QuicheConn,
        path: String,
        title: String,
        desc: String,
    ): Boolean =
        Arena.ofConfined().use { arena ->
            ConnCalls.hConnSetQlogPath.invokeExact(
                conn.handle,
                cString(arena, path).address(),
                cString(arena, title).address(),
                cString(arena, desc).address(),
            ) as Boolean
        }

    override fun configLogKeys(config: QuicheConfig) {
        ConfigCalls.hConfigLogKeys.invokeExact(config.handle)
    }

    override fun connSetKeylogPath(
        conn: QuicheConn,
        path: String,
    ): Boolean =
        Arena.ofConfined().use { arena ->
            ConnCalls.hConnSetKeylogPath.invokeExact(conn.handle, cString(arena, path).address()) as Boolean
        }

    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = ConfigCalls.hLoadCert.invokeExact(config.handle, pathAddr) as Int

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = ConfigCalls.hLoadKey.invokeExact(config.handle, pathAddr) as Int

    override fun configLoadVerifyLocationsFromFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = ConfigCalls.hLoadVerify.invokeExact(config.handle, pathAddr) as Int

    override fun configLoadVerifyLocationsFromDirectory(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int = ConfigCalls.hLoadVerifyDirectory.invokeExact(config.handle, pathAddr) as Int

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
        ServerCalls.hHeaderInfo.invokeExact(
            buf,
            bufLen.toLong(),
            dcil.toLong(),
            versionOut,
            typeOut,
            scidOut,
            scidLenOut,
            dcidOut,
            dcidLenOut,
            tokenOut,
            tokenLenOut,
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
            ServerCalls.hAccept.invokeExact(
                scidAddr,
                scidLen.toLong(),
                odcidAddr,
                odcidLen.toLong(),
                localAddr,
                localAddrLen,
                peerAddr,
                peerAddrLen,
                config.handle,
            ) as Long
        return QuicheConn(result)
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
            ServerCalls.hNegotiateVersion.invokeExact(
                scidAddr,
                scidLen.toLong(),
                dcidAddr,
                dcidLen.toLong(),
                outAddr,
                outLen.toLong(),
            ) as Long
        ).toInt()

    override fun retry(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        newScidAddr: Long,
        newScidLen: Int,
        tokenAddr: Long,
        tokenLen: Int,
        version: Int,
        outAddr: Long,
        outLen: Int,
    ): Int =
        (
            ServerCalls.hRetry.invokeExact(
                scidAddr,
                scidLen.toLong(),
                dcidAddr,
                dcidLen.toLong(),
                newScidAddr,
                newScidLen.toLong(),
                tokenAddr,
                tokenLen.toLong(),
                version,
                outAddr,
                outLen.toLong(),
            ) as Long
        ).toInt()

    override fun connReadable(conn: QuicheConn): QuicheStreamIter =
        QuicheStreamIter(HotCalls.hConnReadable.invokeExact(conn.handle) as Long)

    override fun connWritable(conn: QuicheConn): QuicheStreamIter =
        QuicheStreamIter(HotCalls.hConnWritable.invokeExact(conn.handle) as Long)

    override fun connStreamWritableNext(conn: QuicheConn): Long = HotCalls.hConnStreamWritableNext.invokeExact(conn.handle) as Long

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? {
        if (iter.isExhausted) return null
        val streamIdOut = streamIdScratch.get()
        val hasNext = HotCalls.hStreamIterNext.invokeExact(iter.handle, streamIdOut.address()) as Boolean
        return if (hasNext) QuicStreamId(streamIdOut.get(JAVA_LONG, 0)) else null
    }

    override fun streamIterNextId(iter: QuicheStreamIter): NextStream {
        if (iter.isExhausted) return NextStream.End
        val streamIdOut = streamIdScratch.get()
        val hasNext = HotCalls.hStreamIterNext.invokeExact(iter.handle, streamIdOut.address()) as Boolean
        return if (hasNext) NextStream.of(QuicStreamId(streamIdOut.get(JAVA_LONG, 0))) else NextStream.End
    }

    /** [streamIterNext]'s out-parameter, one per thread so no two calls share it and no call allocates. */
    private val streamIdScratch: ThreadLocal<MemorySegment> = ThreadLocal.withInitial { Arena.ofAuto().allocate(JAVA_LONG) }

    override fun streamIterFree(iter: QuicheStreamIter) {
        if (!iter.isExhausted) HotCalls.hStreamIterFree.invokeExact(iter.handle)
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

    /**
     * `calloc(1, size)` as the address of [size] zero-filled bytes, matching what `quiche_jni.c` gets
     * from its own `calloc`. Its lifetime is this class's to manage through [LibcCalls.hFree]; no arena owns it.
     */
    private fun callocOrThrow(size: Long): Long {
        val raw = LibcCalls.hCalloc.invokeExact(1L, size) as Long
        if (raw == 0L) {
            throw OutOfMemoryError("calloc(1, $size) for a quiche info struct returned NULL")
        }
        return raw
    }

    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo {
        // Make the struct OWN its sockaddr bytes: allocate the 32-byte quiche_recv_info followed by
        // inline copies of `from` and `to`, and point the struct's pointers inside this segment.
        // quiche_conn_recv then dereferences memory this allocation owns, NOT the caller's
        // DirectByteBuffer-backed sockaddrs, which the GC Cleaner or cache eviction can free while a
        // queued packet still references the recv_info (intermittent SIGSEGV in std_addr_from_c under
        // connection churn). from/to are immutable per recv_info, so a one-time copy is sufficient.
        // One allocation for the struct AND its inline sockaddrs, so one free releases all of it.
        val info = callocOrThrow(RECV_INFO_SIZE.toLong() + fromAddrLen + toAddrLen)
        val fromOffset = RECV_INFO_SIZE.toLong() // 32, 8-aligned
        val toOffset = fromOffset + fromAddrLen // 4-aligned (fromAddrLen is 16 or 28)
        MemorySegment.copy(NATIVE, fromAddr, NATIVE, info + fromOffset, fromAddrLen.toLong())
        MemorySegment.copy(NATIVE, toAddr, NATIVE, info + toOffset, toAddrLen.toLong())
        NATIVE.set(JAVA_LONG, info, info + fromOffset) // from -> inline copy
        NATIVE.set(JAVA_INT, info + 8, fromAddrLen) // from_len
        NATIVE.set(JAVA_LONG, info + 16, info + toOffset) // to -> inline copy
        NATIVE.set(JAVA_INT, info + 24, toAddrLen) // to_len
        return QuicheRecvInfo(info)
    }

    /**
     * Releases the struct and the sockaddr copies [recvInfoNew] allocated with it, in one `free`.
     *
     * A NULL handle is a no-op, exactly as `free(NULL)` is in C and as the JNI backend's
     * `nRecvInfoFree` already is. Freeing a handle twice is undefined here in the same way it is on
     * every other backend; `RecvInfoLifecycleGuard` (`-Dquic.recvInfoGuard=1`) is the designated
     * detector for that, and duplicating it on this path would put a debug-only map lookup on a
     * production hot path.
     */
    override fun recvInfoFree(info: QuicheRecvInfo) {
        LibcCalls.hFree.invokeExact(info.handle)
    }

    override fun sendInfoNew(): QuicheSendInfo = QuicheSendInfo(callocOrThrow(SEND_INFO_SIZE.toLong()))

    /** Releases the struct [sendInfoNew] allocated. See [recvInfoFree] for the NULL / double-free contract. */
    override fun sendInfoFree(info: QuicheSendInfo) {
        LibcCalls.hFree.invokeExact(info.handle)
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

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int = NATIVE.get(JAVA_INT, info.handle + SEND_INFO_TO_LEN_OFFSET)

    override fun sendInfoFromAddr(info: QuicheSendInfo): Long {
        // from sockaddr_storage is the first field of quiche_send_info (offset 0).
        return info.handle
    }

    override fun sendInfoFromAddrLen(info: QuicheSendInfo): Int = NATIVE.get(JAVA_INT, info.handle + SEND_INFO_FROM_LEN_OFFSET)

    override fun sendInfoAtNanos(info: QuicheSendInfo): Long =
        NATIVE.get(JAVA_LONG, info.handle + SEND_INFO_AT_OFFSET) * 1_000_000_000L +
            NATIVE.get(JAVA_LONG, info.handle + SEND_INFO_AT_OFFSET + 8)

    // --- sockaddr decode (slice 3 migration) ---
    // FFM reads the struct directly. The JVM runs on Linux/macOS/Windows, so this
    // must mirror SockAddrUtil's encode layout: BSD puts sin_len at byte 0 and
    // sa_family at byte 1; Linux/Windows put sa_family as a uint16 at byte 0.
    // AF_INET = 2 everywhere; AF_INET6 = 10 (Linux) / 30 (BSD) / 23 (Windows).
    // sin_port is at offset 2, sin_addr at 4, sin6_addr at 8 in both layouts.

    private fun u8(addr: Long): Int = NATIVE.get(JAVA_BYTE, addr).toInt() and 0xFF

    private fun beLong(addr: Long): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or u8(addr + i).toLong()
        return v
    }

    override fun sockAddrFamily(addr: Long): Int {
        val fam = if (IS_BSD) u8(addr + 1) else u8(addr) or (u8(addr + 1) shl 8)
        return when (fam) {
            AF_INET -> 4
            AF_INET6 -> 6
            else -> 0
        }
    }

    override fun sockAddrPort(addr: Long): Int = (u8(addr + 2) shl 8) or u8(addr + 3)

    override fun sockAddrV4(addr: Long): Long {
        var v = 0L
        for (i in 4 until 8) v = (v shl 8) or u8(addr + i).toLong()
        return v
    }

    override fun sockAddrV6Hi(addr: Long): Long = beLong(addr + 8)

    override fun sockAddrV6Lo(addr: Long): Long = beLong(addr + 16)

    override fun connActivePathRttNanos(conn: QuicheConn): Long {
        val out = outParams.get().pathStats
        var index = 0L
        while (HotCalls.hConnPathStats.invokeExact(conn.handle, index++, out.address()) as Int >= 0) {
            if (out.get(JAVA_BOOLEAN, 280)) return out.get(JAVA_LONG, 344)
        }
        return NO_RTT_NANOS
    }

    override fun connActivePathProbes(conn: QuicheConn): Long {
        val out = outParams.get().pathStats
        var index = 0L
        while (HotCalls.hConnPathStats.invokeExact(conn.handle, index, out.address()) as Int >= 0) {
            if (out.get(JAVA_BOOLEAN, 280)) return activePathProbesOf(index, out.get(JAVA_LONG, 320))
            index++
        }
        return NO_ACTIVE_PATH
    }

    companion object {
        /**
         * One immortal global-scope segment spanning the whole address space (`NULL.reinterpret(MAX)`), so
         * a quiche pointer held as a `Long` is read or written as `NATIVE.get(layout, address)` with no
         * `MemorySegment.ofAddress` per access. It carries no bounds safety of its own: every access is
         * to an address and length that this class computed from quiche's own struct layouts.
         */
        private val NATIVE: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

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

        /** What `quiche_pin_monotonic_time_nanos` returns when it pinned. */
        private const val PINNED = 1
        private const val CONN_STATS_SIZE = 208L // quiche_stats: 25×8-byte fields + trailing bool, 8-aligned

        // quiche_transport_params layout (64-bit): 10 consecutive uint64 fields [0..72], then the
        // `peer_disable_active_migration` bool at [80] (padded to 8), then peer_active_conn_id_limit
        // [88] and the ssize_t peer_max_datagram_frame_size [96] → 104 bytes, 8-aligned.
        //
        // ⚠️ This layout only holds because the module's build patches `#[repr(C)]` onto quiche's Rust
        // `TransportParams` (patchQuicheTransportParamsRepr in build.gradle.kts). Upstream omits it, and
        // repr(Rust) sinks the 1-byte bool to offset 96 while sizeof stays 104 — so a stale libquiche
        // makes this read peer_active_conn_id_limit as the bool and silently disables migration.
        // `CoreBackendBindingTests` asserts the neighbour fields against configured values, which
        // is the only thing that catches it (a size assertion cannot).
        private const val TRANSPORT_PARAMS_SIZE = 104L
        private const val TRANSPORT_PARAMS_DISABLE_ACTIVE_MIGRATION_OFFSET = 80L
        private const val PATH_STATS_SIZE = 448L // quiche_path_stats: 2×sockaddr_storage blocks + 22×8-byte fields
        private const val RECV_INFO_SIZE = 32
        private const val SEND_INFO_SIZE = 288
        private const val SEND_INFO_TO_OFFSET = 136 // after sockaddr_storage(128) + socklen_t(4) + pad(4)
        private const val SEND_INFO_TO_LEN_OFFSET = 264 // after to sockaddr_storage(128)
        private const val SEND_INFO_AT_OFFSET = 272 // struct timespec after to_len(4) + pad(4)
        private const val SEND_INFO_FROM_LEN_OFFSET = 128 // after from sockaddr_storage(128)

        /**
         * The backend over the process-wide libquiche: [FfmLibrary] opens it once, so every downcall handle is a
         * constant the JIT can inline.
         */
        fun create(): FfmQuicheApi = FfmQuicheApi(FfmLibrary)
    }
}
