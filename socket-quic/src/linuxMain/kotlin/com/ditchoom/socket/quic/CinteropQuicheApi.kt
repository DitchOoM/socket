@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_accept
import com.ditchoom.socket.quic.quiche.quiche_config_discover_pmtu
import com.ditchoom.socket.quic.quiche.quiche_config_enable_dgram
import com.ditchoom.socket.quic.quiche.quiche_config_enable_early_data
import com.ditchoom.socket.quic.quiche.quiche_config_enable_hystart
import com.ditchoom.socket.quic.quiche.quiche_config_enable_pacing
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_grease
import com.ditchoom.socket.quic.quiche.quiche_config_load_cert_chain_from_pem_file
import com.ditchoom.socket.quic.quiche.quiche_config_load_priv_key_from_pem_file
import com.ditchoom.socket.quic.quiche.quiche_config_load_verify_locations_from_file
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_active_connection_id_limit
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_config_set_cc_algorithm
import com.ditchoom.socket.quic.quiche.quiche_config_set_disable_active_migration
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_congestion_window_packets
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_data
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_stream_data_bidi_local
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_stream_data_bidi_remote
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_stream_data_uni
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_streams_bidi
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_streams_uni
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_connection_window
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_idle_timeout
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_pacing_rate
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_recv_udp_payload_size
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_send_udp_payload_size
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_stream_window
import com.ditchoom.socket.quic.quiche.quiche_config_verify_peer
import com.ditchoom.socket.quic.quiche.quiche_conn_available_dcids
import com.ditchoom.socket.quic.quiche.quiche_conn_close
import com.ditchoom.socket.quic.quiche.quiche_conn_dgram_max_writable_len
import com.ditchoom.socket.quic.quiche.quiche_conn_dgram_recv
import com.ditchoom.socket.quic.quiche.quiche_conn_dgram_recv_front_len
import com.ditchoom.socket.quic.quiche.quiche_conn_dgram_send
import com.ditchoom.socket.quic.quiche.quiche_conn_free
import com.ditchoom.socket.quic.quiche.quiche_conn_is_closed
import com.ditchoom.socket.quic.quiche.quiche_conn_is_established
import com.ditchoom.socket.quic.quiche.quiche_conn_is_timed_out
import com.ditchoom.socket.quic.quiche.quiche_conn_migrate
import com.ditchoom.socket.quic.quiche.quiche_conn_migrate_source
import com.ditchoom.socket.quic.quiche.quiche_conn_new_scid
import com.ditchoom.socket.quic.quiche.quiche_conn_on_timeout
import com.ditchoom.socket.quic.quiche.quiche_conn_path_event_next
import com.ditchoom.socket.quic.quiche.quiche_conn_probe_path
import com.ditchoom.socket.quic.quiche.quiche_conn_readable
import com.ditchoom.socket.quic.quiche.quiche_conn_recv
import com.ditchoom.socket.quic.quiche.quiche_conn_scids_left
import com.ditchoom.socket.quic.quiche.quiche_conn_send
import com.ditchoom.socket.quic.quiche.quiche_conn_send_ack_eliciting
import com.ditchoom.socket.quic.quiche.quiche_conn_stream_recv
import com.ditchoom.socket.quic.quiche.quiche_conn_stream_send
import com.ditchoom.socket.quic.quiche.quiche_conn_stream_shutdown
import com.ditchoom.socket.quic.quiche.quiche_conn_timeout_as_nanos
import com.ditchoom.socket.quic.quiche.quiche_conn_writable
import com.ditchoom.socket.quic.quiche.quiche_connect
import com.ditchoom.socket.quic.quiche.quiche_header_info
import com.ditchoom.socket.quic.quiche.quiche_negotiate_version
import com.ditchoom.socket.quic.quiche.quiche_path_event_closed
import com.ditchoom.socket.quic.quiche.quiche_path_event_failed_validation
import com.ditchoom.socket.quic.quiche.quiche_path_event_free
import com.ditchoom.socket.quic.quiche.quiche_path_event_new
import com.ditchoom.socket.quic.quiche.quiche_path_event_peer_migrated
import com.ditchoom.socket.quic.quiche.quiche_path_event_type
import com.ditchoom.socket.quic.quiche.quiche_path_event_validated
import com.ditchoom.socket.quic.quiche.quiche_recv_info
import com.ditchoom.socket.quic.quiche.quiche_send_info
import com.ditchoom.socket.quic.quiche.quiche_stream_iter_free
import com.ditchoom.socket.quic.quiche.quiche_stream_iter_next
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.NativePtr
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Linux [QuicheApi] implementation backed by K/N cinterop.
 *
 * Converts between [Long]-based handles and [kotlinx.cinterop.CPointer] types.
 * All `memScoped` blocks use stack allocation — fast, no GC pressure.
 */
internal object CinteropQuicheApi : QuicheApi {
    private fun Long.toNativePtr(): NativePtr = requireNotNull(this.toCPointer<ByteVar>()) { "null pointer" }.rawValue

    // --- Config ---

    override fun configNew(version: Int): QuicheConfig =
        QuicheConfig(quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())!!.rawValue.toLong())

    override fun configFree(config: QuicheConfig) = quiche_config_free(config.handle.toCPointer()!!)

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = quiche_config_set_application_protos(config.handle.toCPointer()!!, protosAddr.toCPointer()!!, protosLen.convert())

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) = quiche_config_set_max_idle_timeout(config.handle.toCPointer()!!, timeout.convert())

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) = quiche_config_set_max_recv_udp_payload_size(config.handle.toCPointer()!!, size.convert())

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) = quiche_config_set_max_send_udp_payload_size(config.handle.toCPointer()!!, size.convert())

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_initial_max_data(config.handle.toCPointer()!!, v.convert())

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_initial_max_stream_data_bidi_local(config.handle.toCPointer()!!, v.convert())

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_initial_max_stream_data_bidi_remote(config.handle.toCPointer()!!, v.convert())

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_initial_max_stream_data_uni(config.handle.toCPointer()!!, v.convert())

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_initial_max_streams_bidi(config.handle.toCPointer()!!, v.convert())

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_initial_max_streams_uni(config.handle.toCPointer()!!, v.convert())

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) = quiche_config_set_disable_active_migration(config.handle.toCPointer()!!, v)

    override fun configSetActiveConnectionIdLimit(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_active_connection_id_limit(config.handle.toCPointer()!!, v.convert())

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) = quiche_config_verify_peer(config.handle.toCPointer()!!, v)

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) = quiche_config_enable_pacing(config.handle.toCPointer()!!, v)

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_max_pacing_rate(config.handle.toCPointer()!!, v.convert())

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) = quiche_config_set_cc_algorithm(config.handle.toCPointer()!!, algo.convert())

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) = quiche_config_enable_hystart(config.handle.toCPointer()!!, v)

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) = quiche_config_set_initial_congestion_window_packets(config.handle.toCPointer()!!, packets.convert())

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_max_connection_window(config.handle.toCPointer()!!, v.convert())

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) = quiche_config_set_max_stream_window(config.handle.toCPointer()!!, v.convert())

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) = quiche_config_discover_pmtu(config.handle.toCPointer()!!, v)

    override fun configEnableEarlyData(config: QuicheConfig) = quiche_config_enable_early_data(config.handle.toCPointer()!!)

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) = quiche_config_grease(config.handle.toCPointer()!!, v)

    override fun configEnableDgram(
        config: QuicheConfig,
        enabled: Boolean,
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) = quiche_config_enable_dgram(config.handle.toCPointer()!!, enabled, recvQueueLen.convert(), sendQueueLen.convert())

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
        val conn =
            quiche_connect(
                serverNameAddr.toCPointer<ByteVar>()?.toKString(),
                scidAddr.toCPointer<UByteVar>()!!,
                scidLen.convert(),
                localAddr.toCPointer()!!,
                localAddrLen.convert(),
                peerAddr.toCPointer()!!,
                peerAddrLen.convert(),
                config.handle.toCPointer()!!,
            ) ?: error("quiche_connect returned null")
        return QuicheConn(conn.rawValue.toLong())
    }

    override fun connFree(conn: QuicheConn) = quiche_conn_free(conn.handle.toCPointer()!!)

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int =
        quiche_conn_recv(
            conn.handle.toCPointer()!!,
            buf.toCPointer<ByteVar>()!!.reinterpret(),
            bufLen.convert(),
            recvInfo.handle.toCPointer()!!,
        ).toInt()

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int =
        quiche_conn_send(
            conn.handle.toCPointer()!!,
            buf.toCPointer<ByteVar>()!!.reinterpret(),
            bufLen.convert(),
            sendInfo.handle.toCPointer()!!,
        ).toInt()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        memScoped {
            val fin = alloc<BooleanVar>()
            val errorCode = alloc<ULongVar>()
            val result =
                quiche_conn_stream_recv(
                    conn.handle.toCPointer()!!,
                    streamId.id.convert(),
                    buf.toCPointer<UByteVar>()!!,
                    bufLen.convert(),
                    fin.ptr,
                    errorCode.ptr,
                )
            return when {
                result > 0 -> StreamRecvResult.Data(result.toInt(), fin.value)
                result == 0L && fin.value -> StreamRecvResult.Data(0, true)
                result.toInt() == QUICHE_ERR_DONE -> StreamRecvResult.Done
                else -> StreamRecvResult.Error(result.toInt())
            }
        }
    }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult {
        memScoped {
            val errorCode = alloc<ULongVar>()
            val result =
                quiche_conn_stream_send(
                    conn.handle.toCPointer()!!,
                    streamId.id.convert(),
                    if (bufLen > 0) buf.toCPointer<UByteVar>() else null,
                    bufLen.convert(),
                    fin,
                    errorCode.ptr,
                ).toInt()
            // quiche fills out_error_code only on STREAM_STOPPED / STREAM_RESET.
            val peerCode =
                if (result == QuicheDriver.QUICHE_ERR_STREAM_STOPPED || result == QuicheDriver.QUICHE_ERR_STREAM_RESET) {
                    errorCode.value.toLong()
                } else {
                    null
                }
            return StreamSendResult(result, peerCode)
        }
    }

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int =
        // The `enum quiche_shutdown` param accepts its integer value via convert(), exactly like
        // configSetCcAlgorithm's enum arg (0 = QUICHE_SHUTDOWN_READ, 1 = QUICHE_SHUTDOWN_WRITE).
        quiche_conn_stream_shutdown(
            conn.handle.toCPointer()!!,
            streamId.id.convert(),
            direction.convert(),
            err.convert(),
        )

    // --- Unreliable datagrams (RFC 9221) ---

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int =
        quiche_conn_dgram_send(
            conn.handle.toCPointer()!!,
            if (bufLen > 0) buf.toCPointer<UByteVar>() else null,
            bufLen.convert(),
        ).toInt()

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        // ssize_t: >= 0 length (datagrams have no FIN), QUICHE_ERR_DONE when none queued, else error.
        val result =
            quiche_conn_dgram_recv(
                conn.handle.toCPointer()!!,
                buf.toCPointer<UByteVar>()!!,
                bufLen.convert(),
            )
        return when {
            result >= 0 -> StreamRecvResult.Data(result.toInt(), false)
            result.toInt() == QUICHE_ERR_DONE -> StreamRecvResult.Done
            else -> StreamRecvResult.Error(result.toInt())
        }
    }

    override fun hasReadableDgram(conn: QuicheConn): Boolean = quiche_conn_dgram_recv_front_len(conn.handle.toCPointer()!!) >= 0

    override fun connDgramMaxWritableLen(conn: QuicheConn): MaxDatagramSize {
        val raw = quiche_conn_dgram_max_writable_len(conn.handle.toCPointer()!!)
        return if (raw < 0) MaxDatagramSize.Unavailable else MaxDatagramSize.Bytes(raw.toInt())
    }

    override fun connIsEstablished(conn: QuicheConn): Boolean = quiche_conn_is_established(conn.handle.toCPointer()!!)

    override fun connIsClosed(conn: QuicheConn): Boolean = quiche_conn_is_closed(conn.handle.toCPointer()!!)

    override fun connIsTimedOut(conn: QuicheConn): Boolean = quiche_conn_is_timed_out(conn.handle.toCPointer()!!)

    override fun connTimeout(conn: QuicheConn): Duration? {
        val nanos = quiche_conn_timeout_as_nanos(conn.handle.toCPointer()!!)
        return if (nanos == ULong.MAX_VALUE) null else nanos.toLong().nanoseconds
    }

    override fun connOnTimeout(conn: QuicheConn) = quiche_conn_on_timeout(conn.handle.toCPointer()!!)

    override fun connSendAckEliciting(conn: QuicheConn): Int = quiche_conn_send_ack_eliciting(conn.handle.toCPointer()!!).toInt()

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int =
        quiche_conn_close(
            conn.handle.toCPointer()!!,
            error is QuicError.ApplicationError,
            error.code.convert(),
            null,
            0.convert(),
        ).toInt()

    // --- Path migration ---

    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int =
        quiche_conn_probe_path(
            conn.handle.toCPointer()!!,
            localAddr.toCPointer()!!,
            localLen.convert(),
            peerAddr.toCPointer()!!,
            peerLen.convert(),
            seqOut.toCPointer<ULongVar>()!!,
        )

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int =
        quiche_conn_new_scid(
            conn.handle.toCPointer()!!,
            scidAddr.toCPointer()!!,
            scidLen.convert(),
            resetTokenAddr.toCPointer()!!,
            retireIfNeeded,
            seqOut.toCPointer<ULongVar>()!!,
        )

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int =
        quiche_conn_migrate(
            conn.handle.toCPointer()!!,
            localAddr.toCPointer()!!,
            localLen.convert(),
            peerAddr.toCPointer()!!,
            peerLen.convert(),
            seqOut.toCPointer<ULongVar>()!!,
        )

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int =
        quiche_conn_migrate_source(
            conn.handle.toCPointer()!!,
            localAddr.toCPointer()!!,
            localLen.convert(),
            seqOut.toCPointer<ULongVar>()!!,
        )

    override fun connAvailableDcids(conn: QuicheConn): Long = quiche_conn_available_dcids(conn.handle.toCPointer()!!).toLong()

    override fun connScidsLeft(conn: QuicheConn): Long = quiche_conn_scids_left(conn.handle.toCPointer()!!).toLong()

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        val ev = quiche_conn_path_event_next(conn.handle.toCPointer()!!) ?: return null
        val t = quiche_path_event_type(ev).value.toInt()
        when (t) {
            0 ->
                quiche_path_event_new(
                    ev,
                    localOut.toCPointer()!!,
                    localLenOut.toCPointer()!!,
                    peerOut.toCPointer()!!,
                    peerLenOut.toCPointer()!!,
                )
            1 ->
                quiche_path_event_validated(
                    ev,
                    localOut.toCPointer()!!,
                    localLenOut.toCPointer()!!,
                    peerOut.toCPointer()!!,
                    peerLenOut.toCPointer()!!,
                )
            2 ->
                quiche_path_event_failed_validation(
                    ev,
                    localOut.toCPointer()!!,
                    localLenOut.toCPointer()!!,
                    peerOut.toCPointer()!!,
                    peerLenOut.toCPointer()!!,
                )
            3 ->
                quiche_path_event_closed(
                    ev,
                    localOut.toCPointer()!!,
                    localLenOut.toCPointer()!!,
                    peerOut.toCPointer()!!,
                    peerLenOut.toCPointer()!!,
                )
            4 -> {
                // ReusedSourceConnectionId: extra old/new-tuple + CID-seq fields out of scope; surface no addresses.
                localLenOut.toCPointer<UIntVar>()!!.pointed.value = 0u
                peerLenOut.toCPointer<UIntVar>()!!.pointed.value = 0u
            }
            5 ->
                quiche_path_event_peer_migrated(
                    ev,
                    localOut.toCPointer()!!,
                    localLenOut.toCPointer()!!,
                    peerOut.toCPointer()!!,
                    peerLenOut.toCPointer()!!,
                )
        }
        quiche_path_event_free(ev)
        return QuichePathEventType.entries[t]
    }

    // --- Server-side ---

    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int =
        quiche_config_load_cert_chain_from_pem_file(
            config.handle.toCPointer()!!,
            pathAddr.toCPointer<ByteVar>()!!.toKString(),
        )

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int =
        quiche_config_load_priv_key_from_pem_file(
            config.handle.toCPointer()!!,
            pathAddr.toCPointer<ByteVar>()!!.toKString(),
        )

    override fun configLoadVerifyLocationsFromFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int =
        quiche_config_load_verify_locations_from_file(
            config.handle.toCPointer()!!,
            pathAddr.toCPointer<ByteVar>()!!.toKString(),
        )

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
        quiche_header_info(
            buf.toCPointer<UByteVar>()!!,
            bufLen.convert(),
            dcil.convert(),
            versionOut.toCPointer<UIntVar>()!!,
            typeOut.toCPointer<UByteVar>()!!,
            scidOut.toCPointer<UByteVar>()!!,
            scidLenOut.toCPointer<ULongVar>()!!,
            dcidOut.toCPointer<UByteVar>()!!,
            dcidLenOut.toCPointer<ULongVar>()!!,
            tokenOut.toCPointer<UByteVar>()!!,
            tokenLenOut.toCPointer<ULongVar>()!!,
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
    ): QuicheConn {
        val conn =
            quiche_accept(
                scidAddr.toCPointer<UByteVar>()!!,
                scidLen.convert(),
                odcidAddr.toCPointer<UByteVar>(), // nullable — null when no retry
                odcidLen.convert(),
                localAddr.toCPointer()!!,
                localAddrLen.convert(),
                peerAddr.toCPointer()!!,
                peerAddrLen.convert(),
                config.handle.toCPointer()!!,
            ) ?: error("quiche_accept returned null")
        return QuicheConn(conn.rawValue.toLong())
    }

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int =
        quiche_negotiate_version(
            scidAddr.toCPointer<UByteVar>()!!,
            scidLen.convert(),
            dcidAddr.toCPointer<UByteVar>()!!,
            dcidLen.convert(),
            outAddr.toCPointer<UByteVar>()!!,
            outLen.convert(),
        ).toInt()

    // --- Stream iteration ---

    override fun connReadable(conn: QuicheConn): QuicheStreamIter {
        val iter = quiche_conn_readable(conn.handle.toCPointer()!!)
        return QuicheStreamIter(iter?.rawValue?.toLong() ?: 0L)
    }

    override fun connWritable(conn: QuicheConn): QuicheStreamIter {
        val iter = quiche_conn_writable(conn.handle.toCPointer()!!)
        return QuicheStreamIter(iter?.rawValue?.toLong() ?: 0L)
    }

    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? {
        if (iter.isExhausted) return null
        memScoped {
            val streamIdVar = alloc<ULongVar>()
            return if (quiche_stream_iter_next(iter.handle.toCPointer()!!, streamIdVar.ptr)) {
                QuicStreamId(streamIdVar.value.toLong())
            } else {
                null
            }
        }
    }

    override fun streamIterFree(iter: QuicheStreamIter) {
        if (!iter.isExhausted) quiche_stream_iter_free(iter.handle.toCPointer()!!)
    }

    // --- Helpers (recvInfo/sendInfo use native heap allocation) ---

    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo {
        // Make the struct OWN its sockaddr bytes: one allocation holding the quiche_recv_info
        // followed by inline copies of `from` and `to`, with the struct's pointers aimed inside it.
        // quiche_conn_recv then reads memory this allocation owns, not the caller's separately-managed
        // sockaddr buffers (which cache eviction / teardown can free while a queued packet still holds
        // the recv_info — the intermittent SIGSEGV in std_addr_from_c). nativeHeap is malloc-backed
        // (>=8-aligned); from/to are immutable per recv_info so a one-time copy suffices. recvInfoFree
        // frees the single block (the struct address == the allocation base).
        val structSize = sizeOf<quiche_recv_info>()
        val raw = kotlinx.cinterop.nativeHeap.allocArray<ByteVar>(structSize + fromAddrLen + toAddrLen)
        val fromStorage = (raw + structSize)!!
        val toStorage = (raw + (structSize + fromAddrLen))!!
        platform.posix.memcpy(fromStorage, fromAddr.toCPointer<ByteVar>(), fromAddrLen.convert())
        platform.posix.memcpy(toStorage, toAddr.toCPointer<ByteVar>(), toAddrLen.convert())
        val info = raw.reinterpret<quiche_recv_info>().pointed
        info.from = fromStorage.reinterpret()
        info.from_len = fromAddrLen.convert()
        info.to = toStorage.reinterpret()
        info.to_len = toAddrLen.convert()
        return QuicheRecvInfo(raw.rawValue.toLong())
    }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        kotlinx.cinterop.nativeHeap.free(info.handle.toCPointer<quiche_recv_info>()!!.rawValue)
    }

    override fun sendInfoNew(): QuicheSendInfo {
        val info = kotlinx.cinterop.nativeHeap.alloc<quiche_send_info>()
        return QuicheSendInfo(info.ptr.rawValue.toLong())
    }

    override fun sendInfoFree(info: QuicheSendInfo) {
        kotlinx.cinterop.nativeHeap.free(info.handle.toCPointer<quiche_send_info>()!!.rawValue)
    }

    override fun sendInfoToAddr(info: QuicheSendInfo): Long {
        val si = info.handle.toCPointer<quiche_send_info>()!!.pointed
        return si.to.ptr.rawValue
            .toLong()
    }

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int {
        val si = info.handle.toCPointer<quiche_send_info>()!!.pointed
        return si.to_len.toInt()
    }

    override fun sendInfoFromAddr(info: QuicheSendInfo): Long {
        val si = info.handle.toCPointer<quiche_send_info>()!!.pointed
        return si.from.ptr.rawValue
            .toLong()
    }

    override fun sendInfoFromAddrLen(info: QuicheSendInfo): Int {
        val si = info.handle.toCPointer<quiche_send_info>()!!.pointed
        return si.from_len.toInt()
    }

    // --- sockaddr decode (slice 3 migration) ---
    // Linux layout is fixed (sa_family uint16 LE at byte 0, sin_port at 2,
    // sin_addr at 4, sin6_addr at 8); read the bytes directly. Apple is out of
    // scope for slice 3, so no BSD branch here.

    private fun u8(
        addr: Long,
        off: Int,
    ): Int =
        addr
            .toCPointer<UByteVar>()!![off]
            .toInt() and 0xFF

    private fun beLong(
        addr: Long,
        off: Int,
    ): Long {
        var v = 0L
        for (i in off until off + 8) v = (v shl 8) or u8(addr, i).toLong()
        return v
    }

    override fun sockAddrFamily(addr: Long): Int =
        when (u8(addr, 0) or (u8(addr, 1) shl 8)) {
            platform.posix.AF_INET -> 4
            platform.posix.AF_INET6 -> 6
            else -> 0
        }

    override fun sockAddrPort(addr: Long): Int = (u8(addr, 2) shl 8) or u8(addr, 3)

    override fun sockAddrV4(addr: Long): Long {
        var v = 0L
        for (i in 4 until 8) v = (v shl 8) or u8(addr, i).toLong()
        return v
    }

    override fun sockAddrV6Hi(addr: Long): Long = beLong(addr, 8)

    override fun sockAddrV6Lo(addr: Long): Long = beLong(addr, 16)

    private const val QUICHE_ERR_DONE = -1
}
