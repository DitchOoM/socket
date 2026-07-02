package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.transport.NetworkId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The single, immutable, injected-once configuration tree for a transport connection.
 *
 * Folds together everything that used to be scattered across `ConnectionOptions`, `SocketOptions`,
 * the global mutable `PlatformSocketConfig` singleton, and the per-call `read(timeout=…)` defaults:
 *
 * - [bufferFactory] — how read buffers are allocated (platform-aware default).
 * - [readPolicy] / [writePolicy] — the deadline policy adopted by the connection's
 *   [com.ditchoom.buffer.flow.ByteSource] / [com.ditchoom.buffer.flow.ByteSink]. A request/response
 *   transport keeps [ReadPolicy.Bounded]; a persistent stream overrides to [ReadPolicy.UntilClosed].
 *   There is no defaulted `read(timeout)` parameter to silently inherit — the policy lives here.
 * - [connectTimeout] — bound on the connect handshake itself.
 * - [tls] — the unified [TlsConfig]; `null` = plaintext.
 * - [io] — platform I/O + TCP knobs ([IoTuning]), injected rather than read from a process-global.
 * - [networkId] — typed identity of the network path this connect happens over
 *   ([com.ditchoom.socket.transport.NetworkId], sealed/exhaustive — never a bare string or null).
 *   Consumed by the transport-selection layer's per-network
 *   [com.ditchoom.socket.transport.CapabilityCache] scope: a demotion learned on one network
 *   (e.g. "this path blocks UDP") is invalidated when [networkId] changes. Defaults to
 *   [com.ditchoom.socket.transport.NetworkId.Unidentified] — the explicit "no cheap network identity"
 *   state, in which the per-network scope is simply disabled (RFC_TRANSPORT_FALLBACK §12). Populated
 *   by the platform `NetworkMonitor`.
 */
data class TransportConfig(
    val bufferFactory: BufferFactory = BufferFactory.Default,
    val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds),
    val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds),
    val connectTimeout: Duration = 15.seconds,
    val tls: TlsConfig? = null,
    val io: IoTuning = IoTuning(),
    val networkId: NetworkId = NetworkId.Unidentified,
) {
    companion object {
        /** Good defaults for interactive protocols (WebSocket, MQTT, HTTP): TCP_NODELAY on. */
        val LOW_LATENCY = TransportConfig(io = IoTuning(tcpNoDelay = true))

        /** TLS with low latency and default certificate validation. */
        fun tlsDefault() = TransportConfig(tls = TlsConfig.DEFAULT, io = IoTuning(tcpNoDelay = true))

        /** TLS with all validation disabled. For development only. */
        fun tlsInsecure() = TransportConfig(tls = TlsConfig.INSECURE, io = IoTuning(tcpNoDelay = true))
    }
}

/**
 * Platform I/O tuning + TCP socket options — the injected replacement for the global mutable
 * `PlatformSocketConfig` singleton and the old `SocketOptions` data class.
 *
 * TCP knobs ([tcpNoDelay], [keepAlive], buffer sizes) and io_uring knobs ([ioQueueDepth] …) and
 * the codec encode-sizing fallback ([defaultBufferSize]) all live here, threaded through
 * [TransportConfig] rather than mutated on a process-global object.
 */
data class IoTuning(
    /** TCP socket option (ex-SocketOptions): disable Nagle's algorithm for low-latency sends. */
    val tcpNoDelay: Boolean? = null,
    /** Enable SO_REUSEADDR. */
    val reuseAddress: Boolean? = null,
    /** Enable TCP keep-alive. */
    val keepAlive: Boolean? = null,
    /** SO_RCVBUF size in bytes. */
    val receiveBuffer: Int? = null,
    /** SO_SNDBUF size in bytes. */
    val sendBuffer: Int? = null,
    /** io_uring knob (ex-PlatformSocketConfig): size of the I/O submission queue (Linux SQ/CQ depth). */
    val ioQueueDepth: Int = 1024,
    /** Maximum retries when the I/O queue is full. */
    val ioQueueRetries: Int = 10,
    /** Base delay between retries when the I/O queue is full (exponential backoff). */
    val ioRetryDelay: Duration = 1.milliseconds,
    /**
     * Read buffer size in bytes. On Linux the default (65536) triggers a one-time SO_RCVBUF query;
     * any other value overrides it. On other platforms it is used directly.
     */
    val readBufferSize: Int = 65536,
    /**
     * Codec encode-sizing fallback (ex-ConnectionOptions.defaultBufferSize): allocation size for the
     * codec send path when [com.ditchoom.buffer.codec.WireSize.BackPatch] is reported (variable-length).
     */
    val defaultBufferSize: Int = 8192,
)
