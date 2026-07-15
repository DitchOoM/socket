@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Build + bind a JVM/Android quiche-backed server, returning it ready to accept. The returned
 * [SharedQuicheServer] owns its full teardown via [SharedQuicheServer.close] (UDP socket, drivers,
 * handler coroutines, config, and — via the `onClose` lambda wired below — the per-call parent scope).
 * The caller (the [withQuicServer] wrapper) only runs the block and calls `close()`.
 *
 * Lives in `commonJvmMain` so both `jvmMain` and `androidMain` reach it via [QuicheEngine.bind]. The
 * routing/accept loop it drives is common ([SharedQuicheServer]); only the JVM-specific bind wiring
 * (native-memory config load, the host-OS sockaddr layout, the injectable backend) is here.
 */
internal suspend fun buildJvmQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
    // Injectable backend — defaults to the process-wide native binding. A test passes a delegating
    // spy (e.g. to gate connRecv) exactly as the client's commonJvmWithQuicConnection already allows.
    api: QuicheApi = loadQuicheApi(),
): SharedQuicheServer {
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.IO)
    var bound = false

    // QUIC I/O needs native-memory buffers (quiche FFI); see BufferFactory.network(). This is not a
    // caller-configurable knob: the quiche JNI/FFM binding dereferences buffer addresses everywhere
    // (cert/key load, header_info out-params, recv buffers, sockaddrs), so a managed/heap factory
    // can't back a QUIC server on ANY platform — including the JVM. See requireNativeMemory().
    val bufferFactory = BufferFactory.network()

    val config = api.configNew(QUICHE_PROTOCOL_VERSION)
    try {
        writeNullTerminatedString(tlsConfig.certChainPath, bufferFactory).use { certBuf ->
            val rc = api.configLoadCertChainFromPemFile(config, certBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }
        writeNullTerminatedString(tlsConfig.privKeyPath, bufferFactory).use { keyBuf ->
            val rc = api.configLoadPrivKeyFromPemFile(config, keyBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            check(rc == 0) { "Failed to load private key: $rc" }
        }

        encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
            api.configSetApplicationProtos(config, alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong(), alpnBuf.remaining())
        }

        applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, config))

        // Bind the shared unconnected server socket via :socket-udp (Phase 6 adapter-first cutover), sized
        // to QUIC datagrams (not the 64 KB UDP ceiling). One channel serves every accepted connection.
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
                codec = SocketAddressCodec(hostOsSockAddrLayout()),
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
        // Bind failed before SharedQuicheServer took ownership of config + scope — release here.
        if (!bound) {
            api.configFree(config)
            parentScope.cancel()
        }
    }
}

internal fun writeNullTerminatedString(
    str: String,
    factory: BufferFactory,
): PlatformBuffer {
    val buf = factory.allocate(str.length + 1)
    buf.writeString(str, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}
