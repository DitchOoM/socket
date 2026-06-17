@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_listener
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_extract_datagram_flow
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_set_new_connection_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_start
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_identity_from_p12
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_get_port
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_set_new_connection_group_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_start
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Network.nw_connection_group_t
import platform.Network.nw_connection_t
import platform.Network.nw_listener_t
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Build + bind an Apple Network.framework QUIC listener, returning an established [AppleQuicServer]
 * ready to accept. The internal [withTimeout] bounds the listener handshake (so the OS-assigned port
 * is known). Shared by [NetworkEngine.bind]; the default-engine `withQuicServer` wrapper runs the
 * block + close.
 *
 * Network.framework models a QUIC server as an [nw_listener_t] created with QUIC parameters. Because
 * QUIC is a multiplexing protocol, each incoming tunnel is delivered to the listener's
 * *new-connection-group* handler as an [nw_connection_group_t] — the SAME multiplex group type the
 * client path ([connectQuicGroup]) uses — so the whole server-side connection, stream, and datagram
 * surface reuses [AppleQuicGroupConnection] / [NWQuicByteStream] verbatim.
 *
 * TLS identity: the listener needs a `sec_identity_t`, which has no public Apple API to build from
 * loose PEM cert+key. We therefore import [QuicTlsConfig.pkcs12Path] (a PKCS#12 bundle of the same
 * chain) via `SecPKCS12Import`. The PEM paths used by the JVM/Linux servers are ignored here. See
 * [nw_helper_quic_identity_from_p12].
 *
 * Requires iOS 15+ / macOS 12+ (the listener group handler).
 */
internal suspend fun buildAppleQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration,
): QuicServer {
    val p12Path =
        tlsConfig.pkcs12Path
            ?: throw IllegalArgumentException(
                "Apple QUIC server requires QuicTlsConfig.pkcs12Path — Network.framework's listener " +
                    "needs a sec_identity_t, which cannot be built from loose PEM cert+key. See issue #112.",
            )
    val p12Data =
        NSData.create(contentsOfFile = p12Path)
            ?: throw IllegalArgumentException("PKCS#12 file not found or unreadable: $p12Path")
    val identity =
        nw_helper_quic_identity_from_p12(p12Data, tlsConfig.pkcs12Password ?: "")
            ?: throw IllegalStateException(
                "Failed to import PKCS#12 identity from $p12Path (wrong password, malformed blob, or no identity).",
            )

    val datagramsEnabled = quicOptions.datagrams != null
    val maxFrameSize: UShort = if (datagramsEnabled) DATAGRAM_FRAME_SIZE_MAX else 0u
    val alpnList: List<Any?> = quicOptions.alpnProtocols
    val keepAliveSeconds = quicOptions.keepAliveInterval?.inWholeSeconds?.toInt() ?: 0

    val listener =
        nw_helper_create_quic_listener(
            host,
            port.toUShort(),
            alpnList,
            identity,
            quicOptions.idleTimeout.inWholeSeconds.toInt(),
            quicOptions.keepAliveInterval?.inWholeSeconds?.toInt() ?: 0,
            maxFrameSize,
        )
            ?: throw SocketConnectionException.Refused(
                host ?: "::",
                port,
                platformError = "Failed to create QUIC listener",
            )

    // Hosts the per-stream phantom-filter coroutines (see filterPhantomAndEnqueue) and is
    // cancelled by AppleQuicServer.close(). Survives the listener; one per server.
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Each accepted QUIC tunnel (group) lands here, already wired + started by the
    // new-connection-group handler below; connections() drains it.
    val acceptedGroups = Channel<AcceptedGroup>(Channel.UNLIMITED)

    // Wire the group handler BEFORE start (the listener forbids setting it after).
    // The block runs on the listener's serial callback queue: configure the not-yet-
    // started group (state + peer-stream handlers), start it, then hand it off.
    nw_helper_quic_listener_set_new_connection_group_handler(listener) { group ->
        val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
        // Server-initiated bidi stream ids are 0x01-based; peer-accepted ids here are
        // synthetic anyway (Network.framework hides the real QUIC stream id).
        val acceptedStreamId = AtomicLong(1L)
        // Durable state bridge (shared with the client path): resolves the handshake await and
        // — once connections() attaches the live connection — drives a post-handshake group
        // failure/cancellation into it, unblocking acceptStream()/streams(). Replaces the old
        // inline handler that only signalled readiness and closed the stream channel.
        val bridge = AppleQuicGroupStateBridge(host ?: "::", port)

        nw_helper_quic_group_set_state_handler(group) { state, _, errorCode, errorDesc ->
            bridge.onState(state, errorCode, errorDesc)
        }
        // Peer-initiated streams → incomingStreams (drained by acceptStream()/streams()),
        // each routed through filterPhantomAndEnqueue to drop Network.framework's hidden
        // initial stream. The block runs on the group's serial callback queue and must not
        // suspend, so the filtering (which reads) happens on serverScope.
        nw_helper_quic_group_set_new_connection_handler(group) { streamConn ->
            serverScope.launch {
                filterPhantomAndEnqueue(streamConn, incomingStreams, acceptedStreamId, keepAliveSeconds)
            }
        }
        nw_helper_quic_group_start(group)
        acceptedGroups.trySend(AcceptedGroup(group, incomingStreams, bridge))
    }

    // Await the listener reaching ready (so the OS-assigned port is known), then build
    // the server. Listener states: 2=ready, 3=failed, 4=cancelled.
    withTimeout(timeout) {
        suspendCancellableCoroutine { cont ->
            nw_helper_quic_listener_set_state_handler(listener) { state, _, errorCode, errorDesc ->
                when (state) {
                    2 -> if (cont.isActive) cont.resume(Unit)
                    3 ->
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SocketConnectionException.Refused(
                                    host ?: "::",
                                    port,
                                    platformError = "QUIC listener failed: code=$errorCode ${errorDesc ?: ""}",
                                ),
                            )
                        }
                    4 -> if (cont.isActive) cont.resumeWithException(QuicCloseException(QuicError.NoError, "QUIC listener cancelled"))
                    else -> {} // invalid=0, waiting=1
                }
            }
            nw_helper_quic_listener_start(listener)
            cont.invokeOnCancellation { nw_helper_quic_listener_cancel(listener) }
        }
    }

    val boundPort = nw_helper_quic_listener_get_port(listener).toInt()

    val server =
        AppleQuicServer(
            listener = listener,
            boundPort = boundPort,
            acceptedGroups = acceptedGroups,
            datagramsEnabled = datagramsEnabled,
            bufferFactory = BufferFactory.network(),
            keepAliveSeconds = keepAliveSeconds,
            scope = serverScope,
        )
    return server
}

/**
 * Start a peer-initiated NW stream, then drop Network.framework's hidden phantom stream.
 *
 * An [nw_connection_group_t] silently reserves a hidden initial QUIC stream you cannot
 * address. When the peer opens its first real stream (a higher stream id) and sends, the
 * QUIC rule "using a stream id implicitly opens all lower-id streams of that type" forces
 * that hidden stream open too, so the receiver sees an EXTRA, empty, immediately-FINned
 * stream alongside the real one. Left unfiltered it breaks the cross-platform contract
 * that one peer [QuicScope.openStream] yields one [QuicScope.acceptStream].
 *
 * We peek the first read: a real stream's first read returns [ReadResult.Data] (replayed
 * to the consumer via [PrefixedByteStream] so no bytes are lost); the phantom — and any
 * stream that produces no data before closing — returns [ReadResult.End]/[ReadResult.Reset]
 * and is dropped. NB: a peer stream opened with a pure FIN and no payload is therefore not
 * delivered — an accepted limitation of NW hiding the real stream id (we cannot otherwise
 * tell it apart from the phantom).
 */
private suspend fun filterPhantomAndEnqueue(
    streamConn: nw_connection_t,
    incomingStreams: Channel<QuicByteStream>,
    acceptedStreamId: AtomicLong,
    keepAliveSeconds: Int,
) {
    nw_helper_quic_start(streamConn)
    val sid = QuicStreamId(acceptedStreamId.getAndAdd(4))
    val raw = NWQuicByteStream(streamConn, sid.id, keepAliveSeconds)
    val first =
        try {
            raw.read(PHANTOM_PEEK_TIMEOUT)
        } catch (_: Throwable) {
            ReadResult.End
        }
    when (first) {
        is ReadResult.Data ->
            incomingStreams.trySend(
                QuicByteStream(sid, PrefixedByteStream(first, raw)),
            )
        else -> nw_helper_quic_cancel(streamConn) // hidden phantom / empty / reset → drop
    }
}

/** Upper bound on waiting for a peer stream's first byte before treating it as droppable. */
private val PHANTOM_PEEK_TIMEOUT: Duration = 30.seconds

/**
 * A [ByteStream] that replays an already-read first [ReadResult.Data] before delegating to
 * [delegate]. Lets [filterPhantomAndEnqueue] peek a stream's first chunk (to identify the
 * phantom) without consuming it from the consumer's perspective.
 */
private class PrefixedByteStream(
    first: ReadResult.Data,
    private val delegate: ByteStream,
) : ByteStream,
    HalfCloseable,
    Resettable {
    private var pending: ReadResult.Data? = first

    override val isOpen: Boolean get() = delegate.isOpen

    override val readPolicy: ReadPolicy get() = delegate.readPolicy

    override val writePolicy: WritePolicy get() = delegate.writePolicy

    override suspend fun read(deadline: Duration): ReadResult {
        pending?.let {
            pending = null
            return it
        }
        return delegate.read(deadline)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = delegate.write(buffer, deadline)

    override suspend fun shutdownSend() {
        (delegate as? HalfCloseable)?.shutdownSend()
    }

    override suspend fun reset(errorCode: Long) {
        // Drop the replay buffer and forward the abrupt reset (RESET_STREAM +
        // STOP_SENDING) so server-accepted streams reset like client streams; a
        // non-resettable delegate just gets a graceful close. (Issue #81.)
        pending = null
        (delegate as? Resettable)?.reset(errorCode) ?: delegate.close()
    }

    override suspend fun close() = delegate.close()
}

/**
 * An incoming QUIC tunnel handed off from the listener's new-connection-group handler.
 *
 * [bridge].established resolves once the group's handshake completes (or throws on
 * failure/cancellation), and once connections() attaches the live connection the bridge also
 * drives post-handshake group failures into it. [incomingStreams] is the per-group channel that
 * [filterPhantomAndEnqueue] feeds with real (non-phantom) peer-initiated streams.
 */
private class AcceptedGroup(
    val group: nw_connection_group_t,
    val incomingStreams: Channel<QuicByteStream>,
    val bridge: AppleQuicGroupStateBridge,
)

/**
 * The Apple QUIC server. Accepted tunnels arrive over [acceptedGroups] (fed by the
 * listener's new-connection-group handler in [withQuicServer]); each is wrapped in a
 * reused [AppleQuicGroupConnection] so server-side streams/datagrams behave exactly
 * like the client's.
 */
private class AppleQuicServer(
    private val listener: nw_listener_t,
    private val boundPort: Int,
    private val acceptedGroups: Channel<AcceptedGroup>,
    private val datagramsEnabled: Boolean,
    private val bufferFactory: BufferFactory,
    private val keepAliveSeconds: Int,
    private val scope: CoroutineScope,
) : QuicServer {
    override val port: Int get() = boundPort

    @Volatile
    private var closed = false

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
        // Structured concurrency: handler lifetime is bound to this connections()
        // invocation, mirroring LinuxQuicServer.connections(). Cancelling the caller
        // cancels every in-flight handler; close() closes acceptedGroups so the loop ends.
        coroutineScope {
            for (accepted in acceptedGroups) {
                launch {
                    // Wait for the handshake; skip groups that failed/cancelled before ready.
                    val isReady =
                        try {
                            accepted.bridge.established.await()
                            true
                        } catch (e: CancellationException) {
                            // connections() is being torn down before this group finished its
                            // handshake — cancel the group here so it isn't leaked (the cleanup
                            // below lives inside the cancelled launch, so it must be NonCancellable).
                            withContext(NonCancellable) {
                                accepted.incomingStreams.close()
                                nw_helper_quic_group_cancel(accepted.group)
                            }
                            throw e
                        } catch (_: Throwable) {
                            false
                        }
                    if (!isReady) {
                        accepted.incomingStreams.close()
                        nw_helper_quic_group_cancel(accepted.group)
                        return@launch
                    }

                    // Extract the one datagram flow only when datagrams are enabled, and
                    // await its readiness so maxDatagramSize() is known before the handler
                    // runs — mirrors connectQuicGroup on the client.
                    val datagramFlow: nw_connection_t? =
                        if (!datagramsEnabled) {
                            null
                        } else {
                            nw_helper_quic_group_extract_datagram_flow(accepted.group, DATAGRAM_FRAME_SIZE_MAX)
                                ?.also { flow -> awaitDatagramFlowReady(flow) }
                        }

                    val conn =
                        AppleQuicGroupConnection(
                            accepted.group,
                            datagramFlow,
                            accepted.incomingStreams,
                            bufferFactory,
                            keepAliveSeconds = keepAliveSeconds,
                        )
                    // Route post-handshake group failures into this connection (replays one that
                    // raced in between ready and here) — unblocks the handler's acceptStream().
                    accepted.bridge.attach(conn)
                    try {
                        conn.handler()
                    } finally {
                        conn.close()
                    }
                }
            }
        }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Stop accepting first, then unblock connections()' for-loop, then stop the
        // phantom-filter coroutines.
        nw_helper_quic_listener_cancel(listener)
        acceptedGroups.close()
        scope.cancel()
    }

    /**
     * Await the extracted datagram flow's readiness. Connection states: 3=ready,
     * 4=failed, 5=cancelled. A failed/cancelled flow resumes normally (null-equivalent):
     * the handler then sees [MaxDatagramSize.Unavailable] rather than hanging.
     */
    private suspend fun awaitDatagramFlowReady(flow: nw_connection_t) {
        suspendCancellableCoroutine { cont ->
            nw_helper_quic_set_state_handler(flow) { state, _, _, _ ->
                when (state) {
                    3, 4, 5 -> if (cont.isActive) cont.resume(Unit)
                    else -> {}
                }
            }
            nw_helper_quic_start(flow)
            cont.invokeOnCancellation { nw_helper_quic_cancel(flow) }
        }
    }
}
