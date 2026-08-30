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
 *   **Which reads it governs**, for a protocol that multiplexes many streams over one connection
 *   (HTTP/3 in `:socket-http3` is the worked case): [readPolicy] bounds a read that is waiting for
 *   something the *peer already owes* — the head of a peer-opened stream (its type prefix, its first
 *   frame), a response to a request in flight. It does **not** bound a read on a stream that is idle
 *   *by design* — a control or QPACK instruction stream, a stream drained only to keep its
 *   flow-control window open — because there ordinary silence is not failure, and a deadline turns it
 *   into one. Those reads pass [kotlin.time.Duration.INFINITE] explicitly and delegate liveness to the
 *   transport's idle timeout. The rule is the same for both roles of a protocol (see #472, #476, #495,
 *   #512, #513): a client and a server facing the same peer behaviour must not disagree about it.
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
 *   from the platform `NetworkMonitor.state` via the total `NetworkState.networkId` projection —
 *   either stamped per connect by `FallbackTransport(networkId = { monitor.state.value.networkId })`,
 *   or set here explicitly (an explicit value always wins over the producer).
 */
data class TransportConfig(
    val bufferFactory: BufferFactory = BufferFactory.Default,
    val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds),
    // Writes default to UntilClosed (suspend / back-pressure), NOT a bounded deadline: a stalled write
    // is connection-global TCP flow control, and the correct response is to suspend the writer until
    // the peer drains, not to fail a usable connection. Only an opt-in WritePolicy.Bounded(d) enforces
    // a deadline — and when that deadline elapses it is *destructive* (auto-close), the deliberate
    // asymmetry with the non-destructive read-timeout contract. See RFC_WRITE_TIMEOUT_CONTRACT.md.
    val writePolicy: WritePolicy = WritePolicy.UntilClosed,
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
    /**
     * How long `CodecConnection.close()` lets its writer flush already-queued messages before closing
     * the stream underneath it (#382).
     *
     * Exists because `send` is a hand-off: without a drain, `close()` races ahead of a just-queued
     * goodbye frame and the common send-DISCONNECT-then-close shape silently loses it. Bounded rather
     * than unlimited because a peer that has stopped reading must not make `close()` hang — that would
     * move the stall from `send` to `close`, which is what the hand-off exists to prevent.
     *
     * Two seconds is generous for draining a bounded queue onto a healthy peer and short enough that a
     * dead one does not visibly delay teardown. Set it to `Duration.ZERO` to close without draining.
     */
    val outboundDrainOnClose: Duration = 2.seconds,
)
