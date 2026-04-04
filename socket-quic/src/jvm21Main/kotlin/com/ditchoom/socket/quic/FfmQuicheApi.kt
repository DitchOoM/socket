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
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS),
        )
    }
    private val hStreamSend by lazy {
        downcall(
            "quiche_conn_stream_send",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_BOOLEAN),
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

    // --- Config ---
    override fun configNew(version: Int): QuicheConfig = QuicheConfig((hConfigNew.invokeExact(version) as MemorySegment).address())

    override fun configFree(config: QuicheConfig) {
        hConfigFree.invokeExact(seg(config.ptr))
    }

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int = hConfigSetAppProtos.invokeExact(seg(config.ptr), seg(protosAddr), protosLen.toLong()) as Int

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) {
        hConfigSetMaxIdleTimeout.invokeExact(seg(config.ptr), timeout)
    }

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        hConfigSetMaxRecvUdp.invokeExact(seg(config.ptr), size)
    }

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {
        hConfigSetMaxSendUdp.invokeExact(seg(config.ptr), size)
    }

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetMaxData.invokeExact(seg(config.ptr), v)
    }

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetBidiLocal.invokeExact(seg(config.ptr), v)
    }

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetBidiRemote.invokeExact(seg(config.ptr), v)
    }

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetUni.invokeExact(seg(config.ptr), v)
    }

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetStreamsBidi.invokeExact(seg(config.ptr), v)
    }

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) {
        hSetStreamsUni.invokeExact(seg(config.ptr), v)
    }

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hDisableMigration.invokeExact(seg(config.ptr), v)
    }

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) {
        hVerifyPeer.invokeExact(seg(config.ptr), v)
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
                seg(config.ptr),
            ) as MemorySegment
        return QuicheConn(result.address())
    }

    override fun connFree(conn: QuicheConn) {
        hConnFree.invokeExact(seg(conn.ptr))
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = (hConnRecv.invokeExact(seg(conn.ptr), seg(buf), bufLen.toLong(), seg(recvInfo.ptr)) as Long).toInt()

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int = (hConnSend.invokeExact(seg(conn.ptr), seg(buf), bufLen.toLong(), seg(sendInfo.ptr)) as Long).toInt()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: Long,
        buf: Long,
        bufLen: Int,
    ): Long =
        Arena.ofConfined().use { arena ->
            val finOut = arena.allocate(JAVA_BOOLEAN)
            val result = hStreamRecv.invokeExact(seg(conn.ptr), streamId, seg(buf), bufLen.toLong(), finOut) as Long
            val fin = finOut.get(JAVA_BOOLEAN, 0)
            if (fin) result or (1L shl 63) else result
        }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: Long,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): Int = (hStreamSend.invokeExact(seg(conn.ptr), streamId, seg(buf), bufLen.toLong(), fin) as Long).toInt()

    override fun connIsEstablished(conn: QuicheConn): Boolean = hIsEstablished.invokeExact(seg(conn.ptr)) as Boolean

    override fun connIsClosed(conn: QuicheConn): Boolean = hIsClosed.invokeExact(seg(conn.ptr)) as Boolean

    override fun connIsTimedOut(conn: QuicheConn): Boolean = hIsTimedOut.invokeExact(seg(conn.ptr)) as Boolean

    override fun connTimeoutAsNanos(conn: QuicheConn): Long = hTimeoutNanos.invokeExact(seg(conn.ptr)) as Long

    override fun connOnTimeout(conn: QuicheConn) {
        hOnTimeout.invokeExact(seg(conn.ptr))
    }

    override fun connClose(
        conn: QuicheConn,
        app: Boolean,
        err: Long,
        reasonAddr: Long,
        reasonLen: Int,
    ): Int = hConnClose.invokeExact(seg(conn.ptr), app, err, seg(reasonAddr), reasonLen.toLong()) as Int

    // --- RecvInfo / SendInfo ---
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo {
        TODO("recvInfoNew — FFM struct layout for quiche_recv_info")
    }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        TODO("recvInfoFree via FFM")
    }

    override fun sendInfoNew(): QuicheSendInfo {
        TODO("sendInfoNew via FFM")
    }

    override fun sendInfoFree(info: QuicheSendInfo) {
        TODO("sendInfoFree via FFM")
    }

    override fun sendInfoToAddr(info: QuicheSendInfo): Long {
        TODO("sendInfoToAddr via FFM")
    }

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int {
        TODO("sendInfoToAddrLen via FFM")
    }

    companion object {
        fun create(libraryPath: String): FfmQuicheApi {
            val arena = Arena.ofAuto()
            val lookup = SymbolLookup.libraryLookup(libraryPath, arena)
            val linker = Linker.nativeLinker()
            return FfmQuicheApi(lookup, linker)
        }
    }
}
