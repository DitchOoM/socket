@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.appleSockAddrLayout
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Build + bind an Apple quiche-backed server over the `:socket-udp` dual-stack POSIX UDP datagram
 * channel (Phase 6 adapter-first cutover). The dual-stack (`::` + `IPV6_V6ONLY=0`) bind lives in
 * `UdpSocket.bind` now, so a client reaching the server over IPv4 or IPv6 is served. The returned
 * [SharedQuicheServer] owns its teardown via [SharedQuicheServer.close]; the `onClose` lambda cancels
 * the per-call parent scope. Shared by [QuicheEngine.bind]; the [withQuicServer] wrapper runs the block
 * + `close()`. The routing/accept loop it drives is common ([SharedQuicheServer]); only the Apple bind
 * wiring is here.
 */
internal suspend fun buildAppleQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
): SharedQuicheServer {
    val api: QuicheApi = CinteropQuicheApi
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.Default)
    var bound = false
    try {
        val bufferFactory = BufferFactory.network()

        val config = api.configNew(QUICHE_PROTOCOL_VERSION)

        // Load TLS cert chain
        writeNullTerminatedString(tlsConfig.certChainPath, bufferFactory).let { certBuf ->
            val rc = api.configLoadCertChainFromPemFile(config, certBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            certBuf.freeNativeMemory()
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }

        // Load TLS private key
        writeNullTerminatedString(tlsConfig.privKeyPath, bufferFactory).let { keyBuf ->
            val rc = api.configLoadPrivKeyFromPemFile(config, keyBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            keyBuf.freeNativeMemory()
            check(rc == 0) { "Failed to load private key: $rc" }
        }

        // ALPN
        val alpnBuf = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
        api.configSetApplicationProtos(config, alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong(), alpnBuf.remaining())
        alpnBuf.freeNativeMemory()

        applyQuicOptions(quicOptions, AppleQuicConfigCalls(config.handle.toCPointer()!!))

        // Bind the shared server socket via :socket-udp (dual-stack by default) with a QUIC-sized receive
        // staging buffer. One channel serves every accepted connection.
        // One recv pool for the whole server, injected as the shared channel's bufferFactory so each
        // datagram is allocated straight from it — the receive loop then routes it with no copy.
        val recvBufPool = QuicheDriver.newRecvBufPool(bufferFactory)
        val channel =
            UdpSocket.bind(host, port, receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE, bufferFactory = recvBufPool)
        val localAddress = channel.localAddress ?: error("bound server channel has no local address")

        val server =
            SharedQuicheServer(
                api = api,
                config = config,
                channel = channel,
                localAddress = localAddress,
                codec = SocketAddressCodec(appleSockAddrLayout),
                bufferFactory = bufferFactory,
                parentScope = parentScope,
                keepAliveInterval = quicOptions.keepAliveInterval,
                // server.close() frees config + drivers; the per-call parent scope is the server's
                // to cancel last, so the withQuicServer wrapper stays a plain block + close().
                onClose = { parentScope.cancel() },
                tuning = tuning,
                recvBufPool = recvBufPool,
            )
        bound = true
        return server
    } finally {
        // On success the server owns parentScope teardown via onClose; release here only on
        // a bind failure before the server took ownership.
        if (!bound) parentScope.cancel()
    }
}

private fun writeNullTerminatedString(
    str: String,
    factory: BufferFactory,
): PlatformBuffer {
    val buf = factory.allocate(str.length + 1)
    buf.writeString(str, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}
