@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.Utf8
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.StreamLossCause
import com.ditchoom.socket.udp.DatagramSendError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

/**
 * Drives a single quiche connection from one coroutine. No mutexes, no polling.
 *
 * The command channel IS the event loop. When no commands arrive, the coroutine
 * is suspended — zero CPU, zero wakeups. Timeouts are integrated via [select]:
 * the driver queries quiche's timeout after each command and waits for either
 * the next command or the timeout to fire — no separate timeout coroutine.
 *
 * Platform-specific I/O is abstracted via [UdpChannel].
 * Platform-specific quiche bindings are abstracted via [QuicheApi].
 *
 * No `closed` boolean — channel closure IS the lifecycle state.
 */
class QuicheDriver(
    rawApi: QuicheApi,
    private val conn: QuicheConn,
    private val bufferFactory: BufferFactory,
    private val recvInfo: QuicheRecvInfo,
    private val sendInfo: QuicheSendInfo,
    private val udpChannel: UdpChannel,
    private val clientMode: Boolean = true,
    private val isServer: Boolean = false,
    /**
     * Reactive keepalive (RFC 9000 §10.1.2): when non-null, after this much inactivity the driver
     * schedules an ack-eliciting PING (resetting both peers' idle timers) so an otherwise-idle
     * connection survives past its idle timeout with no application traffic. The PING is timed off
     * the driver's own [select] loop — no polling. Null disables keepalive. See [QuicOptions.keepAliveInterval].
     */
    private val keepAliveInterval: Duration? = null,
    /**
     * Read/write deadline policy applied to every [QuicheStreamByteStream] this driver constructs —
     * both streams this side opens ([DriverQuicConnection]/the platform `open()` sites) and streams
     * the peer opens ([discoverNewStreams]). Mirrors [QuicOptions.persistentStreams]: defaults to the
     * pre-existing request/response `Bounded` deadline, so a caller that doesn't opt in sees no
     * behavior change. See [QuicOptions.persistentStreams] for why this is a separate knob from
     * [keepAliveInterval] / the connection idle timeout — a stream-level read deadline is not reset
     * by connection-level keepalive activity (a PING carries no stream data).
     */
    internal val streamReadPolicy: ReadPolicy = ReadPolicy.Bounded(QuicheStreamByteStream.DEFAULT_STREAM_DEADLINE),
    internal val streamWritePolicy: WritePolicy = WritePolicy.Bounded(QuicheStreamByteStream.DEFAULT_STREAM_DEADLINE),
    /**
     * The driver's clock seam (monotonic mark + the `select` timeout clause). Defaults to
     * [RealDriverClock] so every platform and production path keeps its exact pre-seam timer
     * behaviour; tests inject a manual clock to make the keepalive/idle timing deterministic.
     */
    private val clock: DriverClock = RealDriverClock,
    /**
     * Context the driver's control loop and per-path UDP reader loops are launched in. Defaults to
     * [Dispatchers.Default] — the pre-seam hardwired dispatcher, so production behaviour is
     * byte-identical. A test passes [kotlin.coroutines.EmptyCoroutineContext] so both loops inherit
     * the caller's (virtual-time) dispatcher and [clock] wakes run on the kotlinx-coroutines-test
     * scheduler. See RFC_DETERMINISTIC_SIMULATION.md §3.1.
     */
    private val driverContext: CoroutineContext = Dispatchers.Default,
    /**
     * Entropy for the stateless-reset tokens minted by [issueSpareCids]. Defaults to
     * [Random.Default]; the simulation harness injects a seeded instance so every token (and, via
     * [generateScid]'s matching parameter, every connection ID) is reproducible per seed.
     */
    private val random: Random = Random.Default,
    /**
     * Opt-in trace capture (RFC_DETERMINISTIC_SIMULATION.md §5, W3). When non-null the driver:
     *  - wraps every path's [UdpChannel] in the recorder's decorator (DGRAM_OUT/DGRAM_IN + typed
     *    IO ERRORs at the single platform-neutral choke point),
     *  - mirrors [state]/[pathState] transitions into the trace (STATE/PATH_STATE, typed close
     *    reasons as ERROR),
     *  - polls quiche path-stats on its existing timer wake and once at teardown (STATS) — no new
     *    timers, zero cost when null.
     * Timestamps come from the recorder's own clock, which callers must construct from the same
     * [clock] seam (one clock per RFC §5; `QuicheDriverTuning` threads both together).
     */
    internal val recorder: QuicTraceRecorder? = null,
    /**
     * Connection-migration wiring (RFC 9000 §9), as one exhaustive answer.
     *
     * **Deliberately has no default.** It replaces five parameters that all defaulted to "disabled"
     * (`udpChannelFactory: UdpChannelFactory? = null` plus four `0L`/`0` sockaddr sentinels), which
     * meant a construction site could stay silent and get a connection that quietly could not migrate.
     * That is how the Apple client shipped without migration for a year: nothing ever asked it. Now a
     * new platform, backend, or test double cannot compile until it states which case applies.
     *
     * `internal` rather than `private` so `QuicCapabilityConformanceTestSuite` can read the **claim** a
     * live connection was built with and check it against what that connection actually does. A
     * declaration nothing verifies is just a comment the compiler happens to type-check: it was
     * `Supported` that Apple would have had to state, and stating it wrongly costs nothing until
     * something measures it. Internal, so this stays inside the module — the seam is a test seam, like
     * [QuicheBackedConnection], not a consumer API.
     */
    internal val migration: MigrationCapability,
    /**
     * The connection's shared [NetworkMonitor][com.ditchoom.socket.NetworkMonitor] observation, when a
     * **client** engine resolved one; `null` on a server-accepted driver and on every test double, both
     * of which then report [NetworkAtClose.NotObserved] — the truthful answer when nothing is observing.
     *
     * Held here, rather than on the connection wrapper, because the value has to be latched at the
     * close transition ([transitionToClosed]) and that transition happens on the driver loop.
     */
    private val networkObservation: ConnectionNetworkObservation = ConnectionNetworkObservation.Unobserved,
    /**
     * Called from [cleanup] after all quiche handles have been freed. Used by callers
     * to release platform-owned memory referenced by [recvInfo] (peer/local sockaddrs)
     * whose raw pointers are cached inside the recv_info struct. The closure itself
     * keeps those Kotlin-side holders strongly reachable for the driver's lifetime —
     * without it, JVM `DirectByteBuffer`-backed sockaddr buffers can be reclaimed by
     * GC mid-connection, leaving recvInfo.from dangling. See: socket-quic JVM panic at
     * quiche/src/ffi.rs:2059 ("unsupported address type").
     */
    private val onCleanup: () -> Unit = {},
    /**
     * Server-only: where this connection's **current** source-connection-id set is published, so the
     * server's DCID→driver routing map is a projection of quiche's own table rather than a ledger
     * replayed from events (#449). Clients leave it null — they demux incoming packets by their
     * per-path socket, not by an app-level DCID map.
     *
     * The map is load-bearing: it is what decides whether a datagram reaches this connection at all.
     * A CID the peer has stopped using must stop routing at the same moment quiche stops recognising
     * it, because a packet legitimately sent *before* the peer retired the CID can arrive after — a
     * RETIRE_CONNECTION_ID travelling a fast new path routinely overtakes data still in flight on the
     * slow old one. A map that still routes it hands quiche a CID it no longer knows, which quiche
     * reports as `InvalidState` and whose `to_wire()` catch-all becomes PROTOCOL_VIOLATION, killing a
     * healthy connection over a packet RFC 9000 §5.2.2 says to drop (#437). And a CID quiche has
     * issued but the map has not learned is the mirror failure: a migrating peer's packets on the new
     * DCID miss the demux entirely, so the PATH_CHALLENGE never arrives and validation fails.
     *
     * This replaces a pair of `onScidIssued`/`onScidRetired` notifications. Two event streams meant
     * the map could be wrong forever if either were dropped or applied out of order, and nothing ever
     * compared the result with quiche. One set-sync has no order to get wrong and repairs itself on
     * the next projection. See [SourceIdSink].
     */
    private val onSourceIds: SourceIdSink? = null,
    /**
     * Per-connection datagram recv buffer pool — mirrors the server-side pool in
     * CommonJvmWithQuicServer. Two acquirers: the client-mode [udpReaderLoop]
     * (server-accepted drivers receive packets via commands.send() from the
     * server's receive loop and never run it), and [DriverDatagramAdapter.receiveDatagram]
     * (both modes) for RFC 9221 application datagrams.
     *
     * Hoisted to a constructor parameter (default: a fresh per-connection pool) so a client build
     * site can create ONE pool and inject it *both* here and into the `:socket-udp` receive channel
     * (`UdpSocket.connect(bufferFactory = recvBufPool)`, since a [BufferPool] *is* a [BufferFactory]):
     * the channel then allocates each datagram straight from this pool and, for an [UdpChannel] that
     * [UdpChannel.ownsReceiveBuffer], hands the pooled buffer to [udpReaderLoop] with no copy — the B2
     * receive-copy elimination. Server-accepted drivers and every test double omit it and get the
     * default per-connection pool, exactly as before.
     *
     * MultiThreaded mode: [udpReaderLoop] acquires on its own Dispatchers.Default
     * coroutine; the driver's [run] loop releases (via [QuicheCmd.RecvPacket]'s
     * `freeNativeMemory()` in [execute] or [failCommand]) on a different
     * Dispatchers.Default coroutine — different threads under load.
     * maxPoolSize=64 → ~87 KB cached (64 × 1350), generous for a single
     * connection's in-flight datagram count.
     *
     * Ownership invariant: [bufferFactory] is a **leaf** factory per the
     * `TransportConfig.bufferFactory` contract — this pool is built *from* it.
     * Never pass an already-pooled factory: wrapping a pool in a pool is the
     * `80575c1` double-wrap regression (the inner pool reclaims on
     * `freeNativeMemory()` while the outer pool's accounting still counts the
     * buffer, so the cap stops bounding RSS). Same shape as the server-side pool
     * in CommonJvmWithQuicServer.
     */
    internal val recvBufPool: BufferPool = newRecvBufPool(bufferFactory),
) {
    /**
     * The quiche FFI. When [clock] is a virtual-time clock ([DriverTime.Virtual]) the backend api is
     * wrapped in [CallerClockQuicheApi] so every connection call pins libquiche's internal clock first
     * (RFC §6.1 caller-clock — QUIC Tier-A bit-exact). A production [RealDriverClock] reports
     * [DriverTime.Real], so the bare backend api is used unchanged and nothing is injected — zero cost.
     */
    private val api: QuicheApi =
        if (clock.quicheTime() is DriverTime.Virtual) CallerClockQuicheApi(rawApi, clock) else rawApi

    val commands = Channel<QuicheCmd>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    val state: StateFlow<QuicConnectionState> = _state

    /**
     * A close reason this driver decided on itself and that quiche cannot carry back to it.
     *
     * [resolveCloseReason] reads the close reason back out of quiche (`peer_error` / `local_error` /
     * `is_timed_out`), which works for every reason that has a transport code — quiche stores what we
     * sent. A [QuicError] with none (`code < 0`: [QuicError.HandshakeTimeout], [QuicError.IdleTimeout],
     * [QuicError.PlatformError]) has nothing truthful to put on the wire but NO_ERROR, and reading
     * NO_ERROR back would report a *graceful* close for a handshake we abandoned. So [execute] remembers
     * the reason here when quiche accepts such a close, and [resolveCloseReason] reports it.
     *
     * A sealed pair rather than a nullable error: "this driver decided nothing" is a case to match on,
     * not an absence. Written and read on the driver loop only — [execute] and [transitionToClosed] —
     * so it needs no publication.
     */
    private sealed interface LocalCloseVerdict {
        /** Every close quiche can describe itself, and every teardown that was not a close. */
        data object None : LocalCloseVerdict

        /** quiche accepted a local close whose [error] only this driver can name. */
        data class Decided(
            val error: QuicError,
        ) : LocalCloseVerdict
    }

    private var localCloseVerdict: LocalCloseVerdict = LocalCloseVerdict.None

    /**
     * Identity latched on the driver loop just before [cleanup] frees the quiche handles.
     *
     * [closeAttribution] is evaluated on CALLER threads — inside every QuicCloseException a
     * teardown hands out — and both identity reads dereference the live conn: [sessionId] is
     * initialized lazily and [wireConnectionId] reads fresh by design. Without the latch, a caller
     * building its close exception after cleanup() has run reads connTraceId/connSourceId off a
     * freed (and possibly reallocated) quiche_conn — a use-after-free observed live during the
     * #401 hunt. Once non-null, the conn may be gone; identity must come from here.
     */
    @kotlin.concurrent.Volatile
    private var latchedIdentity: QuicConnectionIdentity? = null

    /**
     * This connection's identity and network correlation, as a value snapshot for a
     * [QuicCloseException].
     *
     * Deliberately excludes the close *reason*: the exception carries that already as its `quicError`,
     * and a second copy here could disagree with it. Also excludes everything internal — no `PathKey`
     * (opaque bits, deliberately not reversible into an address) and no native connection handle.
     */
    internal fun closeAttribution(): QuicCloseAttribution =
        QuicCloseAttribution.Attributed(
            identity = latchedIdentity ?: QuicConnectionIdentity(session = sessionId, wire = wireConnectionId),
            network = NetworkAtClose.NotObserved,
        )

    /**
     * This connection's [QuicSessionId] — quiche's stable trace id, read once and cached.
     *
     * Cached because it does not change: quiche documents `quiche_conn_trace_id` as "a string uniquely
     * representing the connection", and unlike the source CID it survives rotation and migration. That
     * stability is exactly what makes it the id you follow one connection by across a reconnect cycle.
     *
     * A backend that does not bind the accessor reports length 0; the session id then falls back to the
     * connection handle, which is still unique within this process and still tells concurrent
     * connections apart — the question the session id exists to answer.
     */
    internal val sessionId: QuicSessionId by lazy {
        QuicSessionId(readConnBytes(asciiText = true) { b, n -> api.connTraceId(conn, b, n) } ?: "conn-${conn.handle.toString(16)}")
    }

    /**
     * The CID currently on the wire, read fresh on every access.
     *
     * Deliberately **not** cached: CIDs rotate, and migration issues a new one by design (RFC 9000
     * §9.5). A cached value would be right until the first handoff and quietly wrong afterwards —
     * which is the moment someone is most likely to be reading it.
     */
    internal val wireConnectionId: QuicWireConnectionId
        get() =
            readConnBytes(asciiText = false) { b, n -> api.connSourceId(conn, b, n) }
                ?.let { QuicWireConnectionId.Known(it) }
                ?: QuicWireConnectionId.Unavailable

    /**
     * Run one of quiche's snprintf-style `(buf, bufLen) -> length` readers and render the result, or
     * `null` when the backend reports nothing (length 0 — including the interface default for a backend
     * that has not bound the accessor).
     *
     * [asciiText] picks the rendering: quiche's trace id is documented as a string, while a connection
     * ID is raw bytes that only mean anything as hex.
     */
    private inline fun readConnBytes(
        asciiText: Boolean,
        read: (Long, Int) -> Int,
    ): String? {
        val buf = bufferFactory.allocate(CONN_ID_TEXT_CAPACITY)
        try {
            val len = read(addr(buf), CONN_ID_TEXT_CAPACITY)
            if (len <= 0 || len > CONN_ID_TEXT_CAPACITY) return null
            val sb = StringBuilder(if (asciiText) len else len * 2)
            repeat(len) {
                val v = buf.readByte().toInt() and 0xFF
                if (asciiText) {
                    sb.append(v.toChar())
                } else {
                    sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
                }
            }
            return sb.toString()
        } finally {
            buf.freeNativeMemory()
        }
    }

    /**
     * The structured QUIC reason to report when an operation fails because the connection is gone:
     * the recorded close reason if the connection has reached [QuicConnectionState.Closed], otherwise
     * one built from [fallback]. Connection state is the single source of truth for the close reason —
     * the driver, [DriverStreamAdapter], and every platform facade funnel through here so a
     * [QuicCloseException] always carries the most specific reason available.
     *
     * Returns the reason, not a bare [QuicError], so **which side closed** survives the throw. It is
     * resolved here, from quiche's `peer_error`/`local_error` (see [resolveCloseReason]), and used to
     * be discarded one line later at every throw site — which is why a post-migration
     * `PROTOCOL_VIOLATION` (#437) could not be told from one the peer sent us.
     */
    fun closeReasonOr(fallback: QuicError): QuicCloseReason {
        val recorded = (state.value as? QuicConnectionState.Closed)?.reason
        // A recorded side is the most specific answer there is; nothing the caller passes beats it.
        if (recorded is QuicCloseReason.ByPeer || recorded is QuicCloseReason.ByLocal) return recorded
        // No recorded failure. A NoError fallback names none either, so the recorded shape stands
        // (Graceful when the protocol said so, Unspecified when nothing did, and Unspecified for a
        // state that is not Closed at all) — the same answer this returned under the old nullable.
        if (fallback is QuicError.NoError) return recorded ?: QuicCloseReason.Unspecified
        // The caller computed this error itself, here, so it is a local one: this endpoint failed the
        // operation, whatever the connection state does or does not say.
        return QuicCloseReason.ByLocal(fallback)
    }

    /**
     * Suspend until the handshake **settles**, and fail if it settled anywhere other than
     * [QuicConnectionState.Established].
     *
     * The documented lifecycle has two exits from `Handshaking` (see [QuicConnectionState]):
     * `→ Established` and `↘ Closed` on handshake failure. Waiting for "no longer `Handshaking`" alone
     * therefore accepts a **dead** connection as a live one: the caller returns from establishment
     * successfully and only discovers the failure at its first stream open, where [closeConnection]
     * has already closed [commands] — so the real reason (idle timeout, crypto error, protocol
     * violation) surfaces as a confusing mid-session `QuicCloseException` from whatever the caller
     * happened to do first, several frames removed from the connect call that actually failed.
     *
     * That misreporting is what made a linuxX64 `:socket-http3` handshake idle-timeout read as an
     * `openUniStream` failure inside `Http3Connection.bootstrap` (release run `30954202211`). Failing
     * here instead keeps the typed [QuicError] attached to the operation that owns it — establishment —
     * so callers can tell "never came up" from "came up, then broke", and an establishment-scoped
     * retry can be written without also swallowing genuine mid-session errors.
     *
     * ## The bound is a close, not a cancellation (#480)
     * The handshake can also fail to settle at all: nothing closes it and [timeout] — the caller's
     * establishment bound — elapses first. With the production shape (a 15s bound against the 30s
     * default idle timeout) that is the timer that actually fires, and this used to let `withTimeout`
     * report it as a bare `TimeoutCancellationException`. That is a `CancellationException`: a
     * `launch` that dies of one is *cancelled*, not failed, so the establishment failure reached no
     * handler (the #472 silent-death mechanism, here on the connect path); the connection was then torn
     * down by scope cancellation and read `Closed(Unspecified)` — the "honest unknown" — for a close
     * whose reason was perfectly well known. The bound now ends the handshake the way every other
     * establishment failure ends it: [abandonHandshake] closes the connection with
     * [QuicError.HandshakeTimeout] and this throws the same reason, so the state channel and the thrown
     * channel agree. The bound is still the caller's — it fires at exactly [timeout], never at the idle
     * timeout — and when quiche's idle timer is the shorter of the two it still wins, exactly as before.
     */
    suspend fun awaitEstablished(timeout: Duration) {
        val settled =
            withTimeoutOrNull(timeout) {
                state.first { it !is QuicConnectionState.Handshaking }
            }
        if (settled == null) {
            val bound = QuicError.HandshakeTimeout(timeout)
            abandonHandshake(bound)
            // The recorded reason wins if the connection settled on its own in the same instant (an idle
            // timeout, a peer close); otherwise it is the bound, recorded by the close just issued.
            throw QuicCloseException(
                closeReasonOr(bound),
                "QUIC handshake did not complete within $timeout",
                attribution = closeAttribution(),
            )
        }
        if (settled !is QuicConnectionState.Established) {
            throw QuicCloseException(closeReasonOr(QuicError.NoError), "QUIC handshake failed", attribution = closeAttribution())
        }
    }

    /**
     * End a handshake the caller's bound gave up on: close through the protocol with [reason] (which
     * [execute] records as this driver's [LocalCloseVerdict] and sends as NO_ERROR — see
     * [QuicheCmd.Close]), then run the driver down so [transitionToClosed] publishes it.
     *
     * The close command, not a scope cancellation, because only a close leaves a reason behind:
     * cancelling the driver is how this case used to end, and it is what produced `Closed(Unspecified)`.
     * A closed command channel means the connection settled on its own first; that reason is already
     * recorded and outranks the bound in [closeReasonOr].
     */
    private suspend fun abandonHandshake(reason: QuicError.HandshakeTimeout) {
        try {
            val closed = CompletableDeferred<Unit>()
            commands.send(QuicheCmd.Close(reason, closed))
            closed.await()
        } catch (_: ClosedSendChannelException) {
            // Settled on its own in the same instant — its own reason stands.
        }
        destroy()
    }

    val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private val streams = mutableMapOf<Long, StreamSlot>()
    private var nextStreamId = if (isServer) 1L else 0L

    // Locally-initiated unidirectional stream IDs (RFC 9000 §2.1): low 2 bits 0b10 (client → 2)
    // or 0b11 (server → 3), stepping by 4. Separate from the bidi counter above.
    private var nextUniStreamId = if (isServer) 3L else 2L

    // --- Unreliable datagrams (RFC 9221) ---

    /**
     * Conflated readiness signal tickled in [afterCommand] when quiche has a datagram queued.
     * A parked [DriverDatagramAdapter.receiveDatagram] waits on it. Conflation makes it
     * lost-wakeup-free: a tickle fired before the receiver parks is buffered until it receives.
     */
    val dgramSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Conflated signal tickled in [afterCommand] after [flushOutgoing] drains the datagram send
     * queue, releasing send backpressure. A [DriverDatagramAdapter.sendDatagram] that got
     * `QUICHE_ERR_DONE` (queue full) parks on it and retries.
     */
    val dgramWritableSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Latest max writable datagram size, refreshed each [afterCommand] (path MTU / negotiation can
     * change it). Read cross-coroutine by `QuicScope.maxDatagramSize()`, hence [Volatile].
     */
    @Volatile
    var lastMaxDatagramSize: MaxDatagramSize = MaxDatagramSize.Unavailable
        private set

    private val udpSendBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
    private val sendAddr = udpSendBuf.nativeMemoryAccess!!.nativeAddress.toLong()
    private var driverJob: Job? = null

    /**
     * Per-connection pool of stream-read buffers. Every [QuicheStreamByteStream] created for this
     * connection (accepted here in [processReadableStreams], or opened by the platform facades) is
     * handed this pool as its `bufferFactory`, so [DriverStreamAdapter.streamRead]'s per-read
     * `allocate(bufferSize)` becomes a pool acquire and the consumer's `freeNativeMemory()` on the
     * delivered buffer becomes a pool return — the exact recycling contract `streamRead` already
     * documents ("a pool-return for pooled factories").
     *
     * Without this, each stream read allocated a fresh [STREAM_READ_BUFFER_SIZE] buffer from the
     * leaf factory. Under the default GC-reclaimed factory those accumulate to the JVM's direct
     * memory cap under high read throughput — the same failure `ReadBufferSource` fixes for the
     * TCP read path — and under a `deterministic()` factory they churn malloc/free per read.
     *
     * MultiThreaded mode: stream readers acquire on their own coroutines; consumers release on
     * theirs. maxPoolSize=16 → ≤1 MB cached (16 × 64 KB) per connection at steady state; misses
     * beyond the cap fall through to the leaf factory and over-cap returns are freed, so the cap
     * bounds cached RSS, not concurrency.
     *
     * Same leaf-factory-in, pool-built-here ownership invariant as [recvBufPool].
     */
    internal val streamReadPool: BufferPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 16,
            defaultBufferSize = STREAM_READ_BUFFER_SIZE,
            factory = bufferFactory,
        )

    /**
     * One network path the connection can send/receive on. The connection always has
     * a [primary] path; active migration ([handleMigrate]) opens more and retires the
     * one migrated from. Routing stays dormant until the first probe ever opens
     * ([routingLive]) — [flushOutgoing] sends straight to [primary] and never decodes —
     * so never-migrating behaviour is byte-for-byte unchanged.
     */
    private inner class PathEntry(
        val key: PathKey,
        val channel: UdpChannel,
        val recvInfo: QuicheRecvInfo,
        val localAddr: Long,
        val localLen: Int,
        val isPrimary: Boolean,
        val release: () -> Unit,
        slot: PathSlot,
    ) {
        var readerJob: Job? = null

        /**
         * Where this path stands, and the destination CID quiche has linked to it while it stands
         * there. Set only through [transitionTo], because every change of this value is also a
         * decision about a connection ID.
         */
        var slot: PathSlot = slot
            private set

        /**
         * Move this path to [next], **retiring the destination CID the previous state held** whenever
         * [next] does not carry that same id forward.
         *
         * The retirement is not something a caller can forget, because it is not a separate step: it
         * happens here, inside the transition, since "this path stopped holding this id" and "this id
         * was retired" are one event. Every exit from a probe — `FailedValidation`, the RFC 9000
         * §8.2.4 abandon timer, a `quiche_conn_migrate` that refuses an already-validated path, and
         * the ordinary supersede-by-the-next-migration — goes through it, which is what closes #447.
         * Only the *success* exit used to retire anything, and it did so at a separate call site with
         * a sequence number kept in a separate holder.
         *
         * Best-effort by design, exactly as the post-migration §9.5 retirement always was: a refusal
         * (`OutOfIdentifiers` when this is the last usable id, or a re-retirement of an id quiche has
         * already dropped) costs one pinned slot, not the transition that has already happened. The
         * systemic check that it works is the conformance suite, not this line.
         */
        fun transitionTo(next: PathSlot) {
            val previous = slot
            slot = next
            when (previous) {
                is PathSlot.Linked ->
                    when (next) {
                        is PathSlot.Linked -> if (next.dcidSeq != previous.dcidSeq) retire(previous.dcidSeq)
                        PathSlot.Abandoned -> retire(previous.dcidSeq)
                    }

                PathSlot.Abandoned -> Unit
            }
        }

        /**
         * The peer answered this path's PATH_CHALLENGE. A validated path holds exactly the connection
         * ID it probed with, so this carries [PathSlot.Linked.dcidSeq] forward and retires nothing —
         * it exists so that the *next* exit (a switch quiche refuses) is a transition out of
         * [PathSlot.Validated] rather than out of a state the path has already left.
         */
        fun validated() {
            when (val current = slot) {
                is PathSlot.Linked -> transitionTo(PathSlot.Validated(current.dcidSeq))
                // Unreachable: `paths` never holds an abandoned entry — teardownPath abandons and
                // removes in one statement — so there is nothing to carry forward and nothing owed.
                PathSlot.Abandoned -> Unit
            }
        }

        private fun retire(dcidSeq: Long) {
            api.connRetireDcid(conn, dcidSeq)
        }
    }

    /** Wrap [channel] in the recording decorator when tracing is on; identity otherwise. */
    private fun tapChannel(
        channel: UdpChannel,
        key: PathKey,
    ): UdpChannel = recorder?.wrap(channel, key.takeIf { it.family != 0 }) ?: channel

    // The primary path's local sockaddr, or null when this driver has no migration wiring. Reading it
    // through the sealed capability is what retires the `0L`-means-absent sentinel: in the Supported
    // branch a PinnedSockAddr exists and is real by construction, and in Unsupported there is no address
    // at all rather than one that happens to be zero.
    private val primaryLocal: PinnedSockAddr? =
        when (migration) {
            is MigrationCapability.Supported -> migration.primaryLocal
            MigrationCapability.ServerConnection,
            MigrationCapability.PolicyForbids,
            MigrationCapability.BackendCannotMigrate,
            -> null
        }

    private val primaryKey =
        primaryLocal?.let { api.decodePathKey(it.address) } ?: PathKey(0, 0, 0L, 0L)

    private val primary =
        PathEntry(
            key = primaryKey,
            channel = tapChannel(udpChannel, primaryKey),
            recvInfo = recvInfo,
            localAddr = primaryLocal?.address ?: 0L,
            localLen = primaryLocal?.length ?: 0,
            isPrimary = true,
            // Primary sockaddr lifetime is owned by the connection setup's onCleanup; nothing to release here.
            release = {},
            // RFC 9000 §5.1.1: the connection starts out using the initial destination CID, sequence 0.
            // That is why a first migration's §9.5 retirement names 0, which PathRetirementTests pins.
            slot = PathSlot.Active(dcidSeq = 0),
        )
    private val paths = mutableMapOf(primary.key to primary)

    /**
     * The migration wiring when this driver has it, else null — one `when` over the sealed capability
     * instead of the four-term boolean this replaced
     * (`clientMode && udpChannelFactory != null && peerAddr != 0L && primaryLocalAddr != 0L`). Every
     * conjunct of that expression is now either impossible to get wrong (the sockaddrs, by
     * [PinnedSockAddr]'s own construction) or stated once at the call site.
     */
    private val migrationWiring: MigrationCapability.Supported? =
        when (migration) {
            is MigrationCapability.Supported -> migration
            MigrationCapability.ServerConnection,
            MigrationCapability.PolicyForbids,
            MigrationCapability.BackendCannotMigrate,
            -> null
        }

    /** True only for a client connection wired for migration. */
    private val migrationEnabled: Boolean = migrationWiring != null

    /**
     * The path the connection is currently living on — [primary] until the first successful
     * migration, then whatever the latest `Validated` arm switched to.
     *
     * Every fallback that used to name [primary] ([flushOutgoing]'s single-path egress, the
     * [PacketSource.Unattributed] recv_info) names this instead: after the primary is retired,
     * [primary] points at a closed socket and a recv_info the connection no longer uses.
     *
     * The DCID sequence in use here is **not** a second field beside this one. It used to be — an
     * `ActivePath(entry, dcidSeq)` holder — and that shape is precisely why only the active path ever
     * had an id anyone could retire (#447). It now lives in [PathEntry.slot], where every path has
     * one, so the §9.5 retirement is a property of leaving a path rather than of one code path
     * remembering to.
     */
    private var active: PathEntry = primary

    /**
     * True once any probe path has ever opened; never true before, never false after. While false,
     * [flushOutgoing] sends straight to [primary] with no decode — single-path behaviour is
     * byte-for-byte what it always was. Once true, every datagram's egress is decoded: after an
     * abandoned probe, quiche keeps scheduling that path's PATH_CHALLENGE for up to 3 PTOs
     * (`MAX_PROBING_TIMEOUTS`) with the driver's `paths` map back to a single entry — so "one path in
     * the map" stops implying "quiche only schedules on that path" (#395 item 4).
     *
     * Derived, not stored: [pathState] leaves [QuicPathState.Original] in the same command that arms
     * the first successful probe (`handleMigrate` publishes `Probing` before any flush can run) and
     * never returns to it, so the flow already carries this fact — a stored boolean would be one more
     * mutable field whose only legal values are implied by state that exists. A probe *rejected* by
     * quiche leaves [QuicPathState.Original] standing, and dormant routing stays correct there:
     * quiche refused the path, so it never schedules on it. The misroute regression test pins this
     * equivalence — a future pathState transition back to Original would turn it red.
     */
    private val routingLive: Boolean get() = _pathState.value != QuicPathState.Original

    private var pendingMigration: PendingMigration? = null

    private val _pathState = MutableStateFlow<QuicPathState>(QuicPathState.Original)
    val pathState: StateFlow<QuicPathState> = _pathState

    /**
     * What the network was doing — live while the connection runs, frozen at [transitionToClosed].
     * [NetworkAtClose.NotObserved] when no monitor was resolved for this driver (a server connection, a
     * test double), which the observation itself reports via
     * [ConnectionNetworkObservation.Unobserved] — there is no second, nullable encoding of that state.
     */
    val networkAtClose: NetworkAtClose get() = networkObservation.atClose

    private var driverScope: CoroutineScope? = null

    // Native scratch for the uint64 seq-out of new_scid/probe/migrate. Allocated for every
    // connection — even non-migrating ones issue spare CIDs so a future peer migration works.
    private val seqScratch: PlatformBuffer = bufferFactory.allocate(8)

    // sockaddr_storage out-words connPathEventNext fills — only migration-capable clients poll events.
    private val peLocalOut: PlatformBuffer? = if (migrationEnabled) bufferFactory.allocate(SOCKADDR_STORAGE_SIZE) else null
    private val peLocalLenOut: PlatformBuffer? = if (migrationEnabled) bufferFactory.allocate(4) else null
    private val pePeerOut: PlatformBuffer? = if (migrationEnabled) bufferFactory.allocate(SOCKADDR_STORAGE_SIZE) else null
    private val pePeerLenOut: PlatformBuffer? = if (migrationEnabled) bufferFactory.allocate(4) else null

    private fun addr(buf: PlatformBuffer?): Long = if (buf == null) 0L else buf.nativeMemoryAccess!!.nativeAddress.toLong()

    /**
     * The migration in flight: which path it opened, the endpoint that path **resolved to** (not the
     * one requested — that distinction is the whole of the `Succeeded(null, 0)` bug), and the caller's
     * deferred, completed by [drainPathEvents] once the peer validates or fails the path.
     *
     * …or, if quiche reports **neither** outcome, by [abandonPathValidationIfDue] once [budget]
     * elapses. Path validation is the one driver operation with no other bound on it: quiche can go
     * silent on a probed path (measured on a real Wi-Fi↔cellular handoff — a probe stuck at
     * `Probing` for the rest of a 2194-line trace), and because [handleMigrate] admits one migration
     * at a time and the automatic reactor awaits each `migrate()` to completion, an unbounded probe
     * parks the reactor for the connection's life: every later network change is either never
     * observed or answered `AlreadyInProgress`. The deadline is timed off [clock] and off the same
     * monotonic-mark shape as `lastActivity`/`keepAliveRemaining`, so it wakes on the driver's
     * existing [select] — no extra timer, no polling.
     */
    private inner class PendingMigration(
        val key: PathKey,
        val localEndpoint: QuicLocalEndpoint,
        val result: CompletableDeferred<MigrationResult>,
        /** RFC 9000 §8.2.4 abandon budget, computed once at arm time by [pathValidationBudget]. */
        private val budget: Duration,
    ) {
        private val armedAt: TimeMark = clock.markNow()

        /** Time left before validation must be abandoned; [Duration.ZERO] once due. Feeds the loop's `wait`. */
        fun validationRemaining(): Duration = (budget - armedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)

        /** Re-*measured*, never inferred from which `select` clause won — see [abandonPathValidationIfDue]. */
        fun isDue(): Boolean = armedAt.elapsedNow() >= budget
    }

    /**
     * How long to let a PATH_CHALLENGE go unanswered before abandoning validation — RFC 9000 §8.2.4:
     *
     * > "Endpoints SHOULD abandon path validation based on a timer. When setting this timer,
     * > implementations are cautioned that the new path could have a longer round-trip time than the
     * > original. A value of three times the larger of the current PTO or the PTO for the new path
     * > (using kInitialRtt, as defined in [QUIC-RECOVERY]) is RECOMMENDED."
     *
     * Derived, never configured. A [QuicOptions] knob would add a number that can contradict the
     * migration policy, and this formula is self-tuning: it widens with the current path's RTT and
     * never drops below the ~3s `kInitialRtt` floor.
     *
     * **The new path's PTO is genuinely unavailable here, and that is the RFC's own `kInitialRtt`
     * branch — not a fallback hack.** This driver only ever reads `pathIdx = 0` (the primary) and
     * keeps no path→index mapping, so there are no RTT samples for a path that has not yet answered
     * a single PATH_CHALLENGE. [QuicheApi.connPathStats] also answers `null` on a backend that has
     * not bound the stats FFI, which lands in exactly the same branch for the same reason: no
     * sample, so assume `kInitialRtt`.
     */
    private fun pathValidationBudget(): Duration {
        val currentPto = api.connPathStats(conn, 0L)?.let { pto(it.rtt, it.rttvar) }
        return maxOf(currentPto ?: INITIAL_PTO, INITIAL_PTO) * PATH_VALIDATION_PTO_MULTIPLIER
    }

    /**
     * RFC 9002 §6.2.1: `PTO = smoothed_rtt + max(4 * rttvar, kGranularity) + max_ack_delay`.
     *
     * `max_ack_delay` is the peer's advertised value (RFC 9000 §18.2), read through the same typed
     * accessor the migration permission check uses; RFC 9002 §6.2.1 requires it be taken as zero
     * until the handshake is confirmed, which is precisely what [PeerTransportParams.NotYetNegotiated]
     * says.
     */
    private fun pto(
        rtt: Duration,
        rttvar: Duration,
    ): Duration {
        val maxAckDelay =
            when (val params = api.connPeerTransportParams(conn)) {
                PeerTransportParams.NotYetNegotiated -> Duration.ZERO
                is PeerTransportParams.Negotiated -> params.maxAckDelayMillis.milliseconds
            }
        return rtt + maxOf(rttvar * 4, K_GRANULARITY) + maxAckDelay
    }

    fun start(scope: CoroutineScope) {
        driverScope = scope
        // Trace capture (RFC §5.1 item 4): mirror the lifecycle StateFlows into the trace. The
        // collectors live on the same context as the driver loop, so under a virtual-time test
        // dispatcher they interleave deterministically; they end when the caller's scope does.
        recorder?.let { r ->
            scope.launch(driverContext) {
                state.collect { s ->
                    r.connectionState(s)
                    if (s is QuicConnectionState.Closed) s.reason.errorOrNull?.let { r.closeError(it) }
                }
            }
            scope.launch(driverContext) {
                pathState.collect { r.pathState(it) }
            }
        }
        driverJob = scope.launch(driverContext) { run() }

        if (clientMode) {
            startReaderLoop(primary)
        }
    }

    private fun startReaderLoop(entry: PathEntry) {
        val scope = driverScope ?: return
        entry.readerJob = scope.launch(driverContext) { udpReaderLoop(entry) }
    }

    /**
     * Enable quiche qlog tracing for this connection when `QUIC_QLOG_DIR` is set (diagnostics only).
     * Writes one `<dir>/quiche-<role>-<conn-handle-hex>.sqlog` per connection — `conn.handle` is the
     * connection's native pointer, unique per live connection, so files never collide. Best-effort: a
     * write/dir failure (or an older libquiche without qlog) just means no trace; it never disrupts the
     * connection. Called once from [run] on the driver coroutine before any packet I/O, honouring quiche's
     * single-threaded contract.
     */
    private fun maybeEnableQlog() {
        val dir = qlogDir() ?: return
        val role = if (isServer) "server" else "client"
        val path = "$dir/quiche-$role-${conn.handle.toString(16)}.sqlog"
        val enabled = api.connSetQlogPath(conn, path, "ditchoom-socket $role", "QUIC_QLOG_DIR trace")
        if (enabled) println("[qlog] tracing $role connection to $path")
    }

    /**
     * The reactive driver loop. Suspends on command channel or quiche timeout — zero CPU when idle.
     * Timeout is integrated via [select]: no separate timeout coroutine, no polling.
     */
    private suspend fun run() {
        try {
            maybeEnableQlog() // diagnostics: env-gated, on the driver coroutine before any packet I/O
            afterCommand() // initial flush (e.g., ClientHello or ServerHello response)
            // Reactive keepalive: time inactivity off a monotonic mark, reset on every command we
            // process. We wake at min(quiche's next timer, keepalive deadline); whichever is sooner
            // decides whether we PING or hand the timeout to quiche. No polling.
            var lastActivity = clock.markNow()
            while (true) {
                val connTimeout = api.connTimeout(conn)
                // Keepalive only counts once the handshake is established (the handshake itself is
                // continuous activity, and a half-open connection has nothing to keep alive).
                val keepAliveRemaining =
                    keepAliveInterval
                        ?.takeIf { api.connIsEstablished(conn) }
                        ?.let { (it - lastActivity.elapsedNow()).coerceAtLeast(Duration.ZERO) }
                // RFC 9000 §8.2.4's abandon timer for an in-flight PATH_CHALLENGE. Null whenever no
                // migration is armed, so a connection that never migrates arms exactly the timers it
                // always did.
                val probeRemaining = pendingMigration?.validationRemaining()
                // Three deadlines, one wake: whichever is soonest decides what the timer branch below
                // does. A `when` over the 2×2 null matrix this replaced does not survive a third term.
                val wait = listOfNotNull(connTimeout, keepAliveRemaining, probeRemaining).minOrNull()
                val cmd =
                    if (wait == null) {
                        // No timer pending — block until next command (or channel close)
                        commands.receiveCatching().getOrNull() ?: break
                    } else {
                        select<QuicheCmd?> {
                            commands.onReceiveCatching { it.getOrNull() }
                            clock.armTimeout(this, wait)
                        }
                    }
                // null from onReceiveCatching means channel closed — exit
                if (cmd == null && commands.isClosedForReceive) break
                when {
                    cmd is QuicheCmd.Migrate -> {
                        // Guarded like execute() below: a throw with the command already dequeued
                        // would otherwise leave its deferred permanently uncompleted (see
                        // failCommandExceptionally).
                        try {
                            handleMigrate(cmd) // suspends: opens a socket
                        } catch (t: Throwable) {
                            failCommandExceptionally(cmd, t)
                            throw t
                        }
                        lastActivity = clock.markNow()
                    }
                    cmd != null -> {
                        try {
                            execute(cmd)
                        } catch (t: Throwable) {
                            failCommandExceptionally(cmd, t)
                            throw t
                        }
                        lastActivity = clock.markNow() // any command is activity → defer keepalive
                    }
                    // A timer fired, and the path-validation deadline is the soonest of the three →
                    // abandon the probe. This arm must sit ABOVE the keepalive one: without it the
                    // wake falls through to `else`, quiche is handed a timeout it did not ask for,
                    // and the expired probe is silently swallowed — which is the shape of the defect,
                    // not a variation on it.
                    probeRemaining != null &&
                        (connTimeout == null || probeRemaining <= connTimeout) &&
                        (keepAliveRemaining == null || probeRemaining <= keepAliveRemaining) -> abandonPathValidationIfDue()
                    // A timer fired. If the keepalive deadline is strictly the sooner one, PING; quiche's
                    // idle timer is always later (keepAliveInterval < idleTimeout), so this fires first and
                    // prevents the idle close. Otherwise hand the (idle/loss-recovery) timeout to quiche.
                    keepAliveRemaining != null && (connTimeout == null || keepAliveRemaining < connTimeout) -> {
                        if (!api.connIsClosed(conn)) {
                            api.connSendAckEliciting(conn) // emitted by the afterCommand() flush below
                            lastActivity = clock.markNow()
                        }
                    }
                    else -> api.connOnTimeout(conn)
                }
                // Trace capture: a timer wake is the periodic-stats sampling point (RFC §5.1 item
                // 5) — the driver already woke, so this adds no timer and costs nothing when off.
                if (cmd == null) {
                    recorder?.let { r -> api.connPathStats(conn, 0L)?.let { r.stats(it) } }
                }
                afterCommand()
            }
        } finally {
            withContext(NonCancellable) {
                // Publish the terminal state FIRST, while `conn` is still alive (cleanup() frees it
                // below) so quiche's peer/local CONNECTION_CLOSE and its timed-out flag are still
                // readable. The loop also exits on paths quiche never reports via connIsClosed — the
                // connection scope being cancelled, or a throw unwinding the loop — and on those only
                // cleanup() used to run, which closes `commands` while leaving `state` on Established
                // forever. Every caller that then hit the closed channel resolved its reason through
                // closeReasonOr, documented as reading `state` as the single source of truth, and so
                // got the NoError fallback: the opaque `QuicCloseException: connection closed` that
                // made the API-35 emulator failure in run 31027926910 undiagnosable. Idempotent — a
                // no-op when the loop already exited through the normal connIsClosed transition.
                transitionToClosed()
                // Readers FIRST: cancel and await every reader loop (primary included) before
                // cleanup() clears the recv pool. A reader parked in receive() holds a pool
                // buffer; if it freed that buffer after clear(), the release would re-pool it
                // into a dead pool (BufferPool has no closed state) and the leaf allocation
                // would never be freed — a real native leak per connection under the
                // explicit-free (deterministic/network()) factories QUIC always uses. Found by
                // the W5 timeline fuzzer (empty-timeline idle close, see SimFuzzSmokeTests).
                // Awaiting here also stops a self-closed connection's reader from lingering
                // until the connection scope dies. Bounded: cancellation unblocks receive() on
                // every UdpChannel (worst case one io_uring submitAndWait tick on Linux).
                for (entry in paths.values.toList()) {
                    entry.readerJob?.cancel()
                }
                for (entry in paths.values.toList()) {
                    entry.readerJob?.join()
                }
                cleanup()
            }
        }
    }

    private fun execute(cmd: QuicheCmd) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> {
                val addr =
                    cmd.buf.nativeMemoryAccess!!
                        .nativeAddress
                        .toLong()
                // Hand quiche the recv_info that tells the truth about where this packet arrived —
                // exhaustive over the three ways a packet enters (see [PacketSource]).
                val source = cmd.source
                val info =
                    when (source) {
                        is PacketSource.FromServerSocket -> source.recvInfo
                        is PacketSource.FromPath -> {
                            val entry = paths[source.key]
                            if (entry == null) {
                                // The packet arrived on a path that has since been retired — a
                                // reader's final datagrams can be queued behind their own teardown.
                                // Feeding it under another path's recv_info would tell quiche it
                                // arrived somewhere it did not (the stale attribution retirement
                                // exists to end); it is bounded collateral of a path the connection
                                // already left. Drop it.
                                cmd.buf.freeNativeMemory()
                                return
                            }
                            entry.recvInfo
                        }
                    }
                api.connRecv(conn, addr, cmd.len, info)
                cmd.buf.freeNativeMemory()
                // Signal the server it may now release the cached recv_info (quiche copied
                // what it needs during connRecv; the pointer is no longer referenced).
                if (source is PacketSource.FromServerSocket) source.onConsumed()
            }

            is QuicheCmd.OpenStream -> {
                val id =
                    if (cmd.unidirectional) {
                        QuicStreamId(nextUniStreamId).also { nextUniStreamId += 4 }
                    } else {
                        QuicStreamId(nextStreamId).also { nextStreamId += 4 }
                    }
                val slot = StreamSlot(id)
                streams[id.id] = slot
                // Make the stream real to quiche now, so that openStream() means what its name says
                // (#423). A QUIC stream becomes known to quiche on its first stream_send; reserving the
                // id here and nowhere else meant a read before the first write asked quiche about a
                // stream it had never heard of, which answered INVALID_STREAM_STATE — reported to the
                // caller first as a clean end-of-stream and then, after #421 stopped that laundering, as
                // a transport failure. Both are wrong for the same reason: the stream has not finished
                // and it has not failed, it has not started. Starting a reader before writing the
                // request is an ordinary shape and simply did not work.
                //
                // A zero-length, non-fin send is the whole materialisation: quiche creates the stream
                // and, with no data and no FIN, the stream is not flushable, so nothing is put on the
                // wire. (Verified separately against quiche's `stream_do_send`, and by the connection
                // byte counters being unchanged across an openStream that is never written to.)
                //
                // The result is CHECKED, not discarded. At the peer's initial_max_streams this send is
                // the call that fails, with QUICHE_ERR_STREAM_LIMIT — and swallowing it put the #423
                // bug straight back at the boundary: openStream() returned a slot quiche had refused to
                // create, and the next read on it answered INVALID_STREAM_STATE, which is exactly the
                // answer this change exists to remove. The typed error is in hand here, so it is
                // reported here.
                val materialised = api.connStreamSend(conn, id, sendAddr, 0, false)
                // Only STREAM_LIMIT. That is the one code which means "this stream cannot be created",
                // which is the only thing this call is here to find out. Every other negative code
                // describes the state of an *existing* stream — STREAM_STOPPED, STREAM_RESET, DONE —
                // and cannot truthfully apply to an id quiche has never seen; treating them as fatal
                // here would move error reporting for cases #423 was never about, off the first real
                // write where it has always belonged.
                if (materialised.result == QUICHE_ERR_STREAM_LIMIT) {
                    // Give the id back: nothing was put on the wire and quiche holds no state for it,
                    // so burning it would leak stream ids on a connection that is merely at its limit.
                    streams.remove(id.id)
                    if (cmd.unidirectional) nextUniStreamId -= 4 else nextStreamId -= 4
                    cmd.result.completeExceptionally(
                        QuicStreamOpenException(
                            streamId = id.id,
                            quicheErrorCode = materialised.result,
                            message =
                                "cannot open QUIC stream ${id.id}: the peer's stream limit is reached " +
                                    "(quiche code ${materialised.result}). Retire or close a stream, " +
                                    "or raise initial_max_streams.",
                        ),
                    )
                } else {
                    cmd.result.complete(slot)
                }
            }

            is QuicheCmd.StreamRecv -> {
                val result = api.connStreamRecv(conn, QuicStreamId(cmd.streamId), cmd.addr, cmd.bufLen)
                cmd.result.complete(result)
            }

            is QuicheCmd.StreamSend -> {
                val sent = api.connStreamSend(conn, QuicStreamId(cmd.streamId), cmd.addr, cmd.bufLen, cmd.fin)
                cmd.result.complete(sent)
            }

            is QuicheCmd.StreamShutdown -> {
                val result = api.connStreamShutdown(conn, QuicStreamId(cmd.streamId), cmd.direction, cmd.errorCode)
                cmd.result.complete(result)
            }

            is QuicheCmd.DgramSend -> {
                val written = api.connDgramSend(conn, cmd.addr, cmd.bufLen)
                cmd.result.complete(written)
            }

            is QuicheCmd.DgramRecv -> {
                val result = api.connDgramRecv(conn, cmd.addr, cmd.bufLen)
                cmd.result.complete(result)
            }

            is QuicheCmd.PeerCert -> {
                // A throwing backend (JNI/cinterop stub until their step lands) must NOT crash the loop or
                // wedge the awaiting caller — complete the deferred exceptionally so connect rethrows.
                try {
                    cmd.result.complete(api.connPeerCert(conn, cmd.addr, cmd.bufLen))
                } catch (t: Throwable) {
                    cmd.result.completeExceptionally(t)
                }
            }

            is QuicheCmd.Stats -> {
                cmd.result.complete(QuicStatsSnapshot(api.connStats(conn), api.connPathStats(conn, 0L)))
            }

            is QuicheCmd.PeerTransportParamsRead -> {
                cmd.result.complete(api.connPeerTransportParams(conn))
            }

            is QuicheCmd.SourceIdsRead -> {
                cmd.result.complete(readSourceIds())
            }

            is QuicheCmd.Close -> {
                val onTheWire = cmd.error.wireCloseError()
                val accepted = api.connClose(conn, onTheWire) == 0
                // Only a close quiche ACCEPTED is this connection's close: a non-zero return means it was
                // already closing for a reason quiche can describe itself, which must not be overridden.
                if (accepted && onTheWire != cmd.error) localCloseVerdict = LocalCloseVerdict.Decided(cmd.error)
                // Sync state from quiche BEFORE signalling the close completed, so a caller
                // awaiting Close() deterministically observes the resulting connection state
                // (Closed once quiche reports the conn closed). Without this, run()'s
                // afterCommand() -> updateState() runs only *after* execute() returns, so the
                // result deferred could complete before the StateFlow flips — a happens-before
                // gap that flaked ReactiveDriverTests.connection_close_sets_closed_state under
                // Dispatchers.Default. updateState() is idempotent, so the afterCommand() call
                // that follows is a harmless no-op; for real quiche (where connIsClosed lags
                // connClose until the close frame drains) this is a no-op here too — state still
                // transitions later via the normal loop, exactly as before.
                updateState()
                cmd.result.complete(Unit)
            }

            is QuicheCmd.Migrate -> handleMigrateSync(cmd) // routed via run() to handleMigrate; defensive only
        }
    }

    /**
     * Unreachable — [run] matches [QuicheCmd.Migrate] *before* calling [execute], so this arm exists
     * only to keep [execute]'s `when` exhaustive. If it ever ran, no path would have been opened, so
     * [MigrationResult.Unmoved.Failed.PathNotValidated] is the honest report: nothing moved, and a
     * later attempt (dispatched correctly) could still succeed.
     */
    private fun handleMigrateSync(cmd: QuicheCmd.Migrate) {
        cmd.result.complete(MigrationResult.Unmoved.Failed.PathNotValidated)
    }

    private suspend fun afterCommand() {
        flushOutgoing()
        if (migrationEnabled) drainPathEvents()
        discoverNewStreams()
        signalWritableStreams()
        signalDatagrams()
        updateState()
    }

    /**
     * Datagram-path mirror of [discoverNewStreams] / [signalWritableStreams]. Refreshes the cached
     * max writable size (read by `maxDatagramSize()`), wakes a parked receiver when quiche has a
     * datagram queued, and — since [flushOutgoing] just drained the datagram send queue — releases
     * any send backpressure. All signals are CONFLATED, so tickling with no parked waiter is a no-op.
     */
    private fun signalDatagrams() {
        lastMaxDatagramSize = api.connDgramMaxWritableLen(conn)
        if (api.hasReadableDgram(conn)) dgramSignal.trySend(Unit)
        dgramWritableSignal.trySend(Unit)
    }

    /**
     * Wake any writer parked on a stream whose flow-control window just reopened. The write-path mirror
     * of [discoverNewStreams]: quiche surfaces newly-writable streams via [QuicheApi.connWritable] (e.g.
     * after a `MAX_STREAM_DATA` / `MAX_DATA` frame arrived in the command we just processed). Unlike the
     * read path this **never creates a slot** — a writable stream we don't track is one nobody is writing
     * to, so there is nothing to wake and a phantom slot would be an impossible state. The signal is
     * CONFLATED, so signalling a stream with no parked writer is a harmless no-op.
     */
    private fun signalWritableStreams() {
        val iter = api.connWritable(conn)
        if (iter.isExhausted) return
        try {
            while (true) {
                val streamId = api.streamIterNext(iter) ?: break
                streams[streamId.id]?.writableSignal?.trySend(Unit)
            }
        } finally {
            api.streamIterFree(iter)
        }
    }

    private fun discoverNewStreams() {
        val iter = api.connReadable(conn)
        if (iter.isExhausted) return
        try {
            while (true) {
                val streamId = api.streamIterNext(iter) ?: break
                val existing = streams[streamId.id]
                if (existing != null) {
                    existing.dataSignal.trySend(Unit)
                } else {
                    val slot = StreamSlot(streamId)
                    streams[streamId.id] = slot
                    val adapter = DriverStreamAdapter(this, slot)
                    val byteStream =
                        QuicheStreamByteStream(
                            streamId,
                            adapter,
                            streamReadPool,
                            readPolicy = streamReadPolicy,
                            writePolicy = streamWritePolicy,
                        )
                    incomingStreams.trySend(QuicByteStream(streamId, byteStream))
                    slot.dataSignal.trySend(Unit)
                }
            }
        } finally {
            api.streamIterFree(iter)
        }
    }

    private fun updateState() {
        if (api.connIsEstablished(conn) && _state.value is QuicConnectionState.Handshaking) {
            _state.value = QuicConnectionState.Established(readNegotiatedAlpn())
        }
        // Not once, but whenever capacity exists: RFC 9000 §5.1.1 says supply a new CID when the peer
        // retires one — which a migrating peer now does on every move (§9.5). Behind a one-shot flag,
        // the peer of a migrating client ran dry after ~MAX_SPARE_SCIDS migrations (#395). The steady
        // state costs one connScidsLeft read per wake, alongside the two state reads above.
        if (_state.value is QuicConnectionState.Established) {
            val issued = issueSpareCids()
            val retired = drainRetiredScids()
            // The routing table is set to what quiche says, not adjusted by what just happened: the
            // two counts only decide WHETHER to look, never what the answer is (#449).
            projectSourceIds(setMayHaveMoved = issued > 0 || retired > 0)
        }
        if (api.connIsClosed(conn)) {
            transitionToClosed()
        }
    }

    /**
     * The handshake's negotiated ALPN protocol (`quiche_conn_application_proto`), read once at the
     * Handshaking→Established edge and published in [QuicConnectionState.Established] — the value
     * behind [QuicConnection.negotiatedAlpn] and the server-side ALPN demux (`connectionsByAlpn`).
     * Empty when the backend does not expose it (a [QuicheApi] test double using the interface
     * default). RFC 7301 caps an identifier at 255 bytes, so one fixed allocation never re-tries.
     */
    private fun readNegotiatedAlpn(): String {
        val buf = bufferFactory.allocate(MAX_ALPN_LEN)
        try {
            val len = api.connApplicationProto(conn, addr(buf), MAX_ALPN_LEN)
            if (len <= 0 || len > MAX_ALPN_LEN) return ""
            buf.setLimit(len)
            // Lenient: this is our own handshake result coming back out of quiche, not peer-framed
            // input with a protocol violation to report, so it must not be able to throw.
            return buf.readText(len, Utf8.Lenient)
        } finally {
            buf.freeNativeMemory()
        }
    }

    /**
     * Transition to [QuicConnectionState.Closed], closing the command channel **before** publishing
     * the new state. These are two coupled signals; a caller/test that keys off `state == Closed`
     * (e.g. `state.first { it is Closed }`) must be able to rely on `commands.isClosedForSend` being
     * true the instant it observes Closed. Publishing the StateFlow value first left a happens-before
     * gap — on the multi-threaded dispatcher an observer could interleave between the two lines and
     * see Closed with the channel still open, which flaked
     * ReactiveDriverTests.flushOutgoing_transitionsToClosedOnUdpError. Closing first makes the
     * channel-close happen-before the state observation (via the StateFlow publication).
     * Idempotent — no-op if already Closed.
     *
     * The stream drain runs **first**, for the same happens-before reason: a reader discovers the
     * teardown by hitting the closed [commands] channel (or the closed [StreamSlot.dataSignal]), so
     * everything quiche still holds for it must already be in [StreamSlot.pendingData] by the time
     * either of those closes. See [drainReadableStreamsIntoSlots].
     */
    private fun transitionToClosed() {
        if (_state.value is QuicConnectionState.Closed) return
        // Latch the network correlation BEFORE anything can observe Closed, so a reader that keys off
        // the state gets what the network was doing when the connection died — not what it is doing when
        // the log line is written. This is the whole point of freezing here rather than in a collector:
        // the connection's scope children are cancelled at close, so a state collector may never run.
        networkObservation.freeze()
        drainReadableStreamsIntoSlots()
        commands.close()
        _state.value = QuicConnectionState.Closed(resolveCloseReason())
    }

    /**
     * Move every byte quiche has already accepted for a tracked stream out of the connection and into
     * that stream's [StreamSlot.pendingData], so a reader still gets it after [cleanup] frees `conn`.
     *
     * The connection ending does not un-receive stream data: quiche keeps a stream's receive buffer
     * readable while the connection drains (`do_stream_recv` has no closed-connection guard), and
     * RFC 9000 §10.2 makes a CONNECTION_CLOSE the end of the *connection*, not a licence to discard
     * bytes the transport already accepted and acknowledged. Before this drain those bytes died with
     * `quiche_conn_free` and the pending `read()` returned `End` — a clean-EOF verdict over data we
     * were still holding. That is issue #318: the client half-closed, the peer replied `ping` and then
     * closed the connection, and a reader whose wakeup lost the race to the teardown reported
     * `no_data:End`; the same window swallows any unread tail on an idle-timeout or peer-close.
     *
     * Runs on the driver loop with `conn` still alive (every [transitionToClosed] caller precedes
     * [cleanup]), so the quiche calls honour the single-threaded contract. Streams quiche reports as
     * readable but that this driver never tracked are skipped — nobody can read them, and minting a
     * phantom slot here would be an impossible state (same rule as [signalWritableStreams]).
     */
    private fun drainReadableStreamsIntoSlots() {
        val iter = api.connReadable(conn)
        if (iter.isExhausted) return
        val readable = mutableListOf<StreamSlot>()
        try {
            while (true) {
                val streamId = api.streamIterNext(iter) ?: break
                streams[streamId.id]?.let { readable += it }
            }
        } finally {
            api.streamIterFree(iter)
        }
        for (slot in readable) drainStreamIntoSlot(slot)
    }

    /** Drain one stream's readable bytes into [StreamSlot.pendingData]. See [drainReadableStreamsIntoSlots]. */
    private fun drainStreamIntoSlot(slot: StreamSlot) {
        while (true) {
            val buffer = streamReadPool.allocate(STREAM_READ_BUFFER_SIZE)
            val result = api.connStreamRecv(conn, slot.id, addr(buffer), STREAM_READ_BUFFER_SIZE)
            // Not Data => the drain for this stream is over. A Reset still latches the verdict
            // (with the peer's code) so a post-teardown read reports the abort, not a clean End (#398);
            // Done and everything else deliver nothing further.
            if (result !is StreamRecvResult.Data) {
                if (result is StreamRecvResult.Reset && slot.end == StreamEnd.Open) {
                    slot.end = StreamEnd.Reset(result.applicationErrorCode)
                }
                buffer.freeNativeMemory()
                return
            }
            if (result.bytesRead > 0) {
                buffer.position(result.bytesRead)
                buffer.resetForRead()
                // UNLIMITED and never closed before this point, so the send cannot fail; on the
                // impossible branch release the buffer rather than leaking it.
                if (slot.pendingData.trySend(buffer).isFailure) {
                    buffer.freeNativeMemory()
                    return
                }
            } else {
                buffer.freeNativeMemory()
            }
            // Carry the FIN the same way the read path does, so the read that follows the drained
            // chunks returns End instead of parking on a dataSignal nothing will ever tickle again.
            // Published *after* the chunk is queued: a reader that sees the flag must also see the
            // data, otherwise the End verdict would race ahead of the bytes it is supposed to follow.
            // Guarded so a drain can never downgrade an already-latched terminal state.
            if (result.fin) {
                if (slot.end == StreamEnd.Open) slot.end = StreamEnd.Fin
                return
            }
            // 0 bytes without a FIN: quiche has nothing more to hand over.
            if (result.bytesRead <= 0) return
        }
    }

    /**
     * The exhaustive [QuicCloseReason] for why the connection closed. Prefers the **peer's**
     * CONNECTION_CLOSE (the remote tore us down — e.g. a strict server rejecting our streams or
     * transport params) over our **local** close (quiche itself aborted — handshake/TLS failure,
     * protocol violation), since the peer's reason is the more actionable one when both exist; the
     * result records which side it came from, which the old bare-[QuicError] return discarded. quiche is
     * single-threaded; this runs on the driver loop alongside [updateState], so the reads are safe.
     *
     * Both helpers are bound on every real backend (FFM, JNI/Android, cinterop). A test double that
     * reports neither error nor timeout now yields [QuicCloseReason.Unspecified] rather than looking
     * like a clean shutdown.
     */
    private fun resolveCloseReason(): QuicCloseReason {
        val peer = api.connPeerError(conn)
        val local = api.connLocalError(conn)
        // Precedence is unchanged: a real (non-NoError) reason wins, peer before local.
        peer?.takeUnless { it is QuicError.NoError }?.let { return QuicCloseReason.ByPeer(it) }
        local?.takeUnless { it is QuicError.NoError }?.let { return QuicCloseReason.ByLocal(it) }
        // A local close whose reason has no transport code went out as NO_ERROR and is only known here
        // (see [LocalCloseVerdict]). Ahead of the NoError → Graceful fold below, which is exactly the
        // misreading it exists to prevent; behind quiche's own errors, which it can only have been
        // recorded in the absence of.
        when (val verdict = localCloseVerdict) {
            is LocalCloseVerdict.Decided -> return QuicCloseReason.ByLocal(verdict.error)
            LocalCloseVerdict.None -> Unit
        }
        // No CONNECTION_CLOSE frame: distinguish an idle/handshake-stall timeout (a local event, no wire
        // code) from a genuinely clean shutdown — otherwise a stalled connection looks like NoError.
        if (api.connIsTimedOut(conn)) return QuicCloseReason.ByLocal(QuicError.IdleTimeout)
        // A CONNECTION_CLOSE carrying NO_ERROR was exchanged — a real graceful shutdown.
        if (peer is QuicError.NoError || local is QuicError.NoError) return QuicCloseReason.Graceful
        // Nothing was exchanged and nothing timed out: the scope was cancelled or a throw unwound the
        // loop. Previously this returned null and read as a clean shutdown, which is what made the
        // API-35 emulator teardown undiagnosable (see the transitionToClosed call site above).
        return QuicCloseReason.Unspecified
    }

    private suspend fun flushOutgoing() {
        while (true) {
            val written = api.connSend(conn, sendAddr, MAX_DATAGRAM_SIZE, sendInfo)
            if (written <= 0) break
            // Route by the local egress address quiche chose. Until the first probe ever opens
            // ([routingLive]) this is dormant — send straight to primary, no decode — so the
            // never-migrating common case is byte-for-byte unchanged. Once a probe has existed,
            // "one path in the map" no longer bounds what quiche schedules on (see [routingLive]),
            // so every datagram is decoded from then on.
            val channel =
                if (!routingLive) {
                    primary.channel
                } else {
                    val from = api.decodePathKey(api.sendInfoFromAddr(sendInfo))
                    if (from.family == 0) {
                        // A backend that exposes no egress address (test doubles decode nothing);
                        // quiche schedules non-probing data on the active path, so honour that.
                        active.channel
                    } else {
                        val entry = paths[from]
                        if (entry == null) {
                            // quiche scheduled this datagram on a path the driver has torn down — an
                            // abandoned probe re-arming its PATH_CHALLENGE for up to 3 PTOs, or a
                            // just-retired path draining its last frames. There is no socket for that
                            // 4-tuple; sending it out any other one answers the peer from an address
                            // it never probed (the misroute), and a dead fallback socket would abort
                            // this whole flush (the stall). It is already lost: skip it and keep
                            // draining — RFC 9002 loss recovery owns it (#395 item 4).
                            continue
                        }
                        entry.channel
                    }
                }
            // Server egress follows the peer: send to the destination quiche chose (sendInfo.to) so
            // a migrated client's new source receives replies. Clients leave this null and rely on
            // their connected/path sockets. NioUdpChannel caches the reconstruction (steady state
            // targets one address), so the non-migrating server path stays allocation-free.
            val dest = if (isServer) api.decodePathKey(api.sendInfoToAddr(sendInfo)) else null
            // A channel reports failure as a value ([SendOutcome]); the `catch` here is only a net for
            // a backend that throws outside that contract. It normalises into the type — it does not
            // decide policy. That decision is the exhaustive `when` below, which is what makes adding
            // a new outcome a compile error rather than a silent inheritance of whatever this branch
            // happened to do. Letting anything escape would leak an uncaught coroutine failure into
            // the parent scope, the original defect this site was written to fix.
            val outcome =
                try {
                    channel.send(udpSendBuf, written, dest)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (untyped: Exception) {
                    SendOutcome.Failed(DatagramSendError.Transport(untyped))
                }
            when (outcome) {
                is SendOutcome.Sent -> Unit // keep draining quiche's send queue
                is SendOutcome.Failed -> {
                    // Stop draining this flush — but do NOT end the connection.
                    //
                    // An undelivered datagram is the most ordinary event in QUIC: loss detection and
                    // retransmission (RFC 9002) exist for exactly this, and a local send failure is
                    // indistinguishable from a packet lost on the wire. RFC 9000 §10 enumerates the
                    // three ways a connection may terminate — idle timeout, immediate close, and
                    // stateless reset — and a failed send is not one of them.
                    //
                    // This site used to call transitionToClosed(), which made two things impossible:
                    // riding out transient backpressure (ENOBUFS/EAGAIN), and active connection
                    // migration at all — a handoff happens *because* the old path died, so the first
                    // send afterwards killed the connection before the new path could be validated.
                    // Termination is left to quiche's idle timer, which reports the truthful
                    // QuicError.IdleTimeout (pinned by IdleTimeoutTerminationTests).
                    return
                }
            }
        }
    }

    /**
     * Client-mode: async UDP reader for one [entry]'s socket. Suspends until data
     * arrives — zero CPU when no packets. Tags each packet with [PathEntry.key] so
     * the driver feeds quiche the right recv_info during migration.
     */
    private suspend fun udpReaderLoop(entry: PathEntry) {
        // A channel that allocates its own receive buffer (from the injected [recvBufPool]) hands the
        // pooled buffer out directly — no driver pre-allocation, no copy (the B2 elimination). Every
        // other channel (test doubles, legacy io_uring/NIO proxies) fills a buffer we pre-allocate.
        if (entry.channel.ownsReceiveBuffer) return ownedBufferReaderLoop(entry)
        val pool = recvBufPool
        var consecutiveFailures = 0
        try {
            while (coroutineContext[Job]?.isActive != false) {
                val buf = pool.allocate(MAX_DATAGRAM_SIZE)
                val received =
                    try {
                        entry.channel.receive(buf)
                    } catch (e: CancellationException) {
                        buf.freeNativeMemory()
                        throw e
                    } catch (_: Exception) {
                        buf.freeNativeMemory()
                        if (commands.isClosedForSend) return
                        if (++consecutiveFailures >= MAX_CONSECUTIVE_RECEIVE_FAILURES) return
                        delay(receiveRetryBackoff(consecutiveFailures))
                        continue
                    }
                consecutiveFailures = 0
                if (received > 0) {
                    try {
                        commands.send(QuicheCmd.RecvPacket(buf, received, PacketSource.FromPath(entry.key)))
                    } catch (e: ClosedSendChannelException) {
                        // The driver closed between our receive() and this enqueue (an idle-timeout
                        // or error close racing an inbound datagram). The packet can never be
                        // processed and cleanup()'s drain never sees it — free it here or the
                        // buffer leaks at the leaf. Found by the W5 timeline fuzzer
                        // (datagram-after-close, see ReaderLoopCloseRaceRegressionTests).
                        buf.freeNativeMemory()
                        throw e
                    }
                } else {
                    // received <= 0 is NOT necessarily terminal here: io_uring's recv returns a negative
                    // errno on the routine 1-second submitAndWait timeout (and -ECANCELED/-EBADF on
                    // re-arm), which the loop must retry — exiting on it would kill the reader after any
                    // >1s quiet period (breaking keepalive/idle/migration). So free + keep looping. A
                    // channel that is genuinely, permanently dead must instead suspend in receive() until
                    // the driver cancels the reader (see AppleNwUdpChannel's terminal park).
                    buf.freeNativeMemory()
                }
            }
        } catch (_: ClosedSendChannelException) {
            // Driver closed
        }
    }

    /**
     * Zero-copy variant of [udpReaderLoop] for an [UdpChannel] that [UdpChannel.ownsReceiveBuffer]:
     * the channel allocated each datagram straight from [recvBufPool] and hands us that pooled buffer,
     * so we enqueue it as-is with no pre-allocation and no copy. [UdpChannel.receiveOwned] absorbs the
     * transient re-arm/timeout retries internally (returning only a real datagram) and suspends
     * indefinitely once the socket is permanently closed — the driver cancels this reader during
     * teardown — so there is no non-positive "keep looping" case to handle here.
     */
    private suspend fun ownedBufferReaderLoop(entry: PathEntry) {
        var consecutiveFailures = 0
        try {
            while (coroutineContext[Job]?.isActive != false) {
                val owned =
                    try {
                        entry.channel.receiveOwned()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (commands.isClosedForSend) return
                        if (++consecutiveFailures >= MAX_CONSECUTIVE_RECEIVE_FAILURES) return
                        delay(receiveRetryBackoff(consecutiveFailures))
                        continue
                    }
                consecutiveFailures = 0
                try {
                    commands.send(QuicheCmd.RecvPacket(owned.buffer, owned.length, PacketSource.FromPath(entry.key)))
                } catch (e: ClosedSendChannelException) {
                    // Same close race as [udpReaderLoop]: the driver closed between receiveOwned() and
                    // this enqueue. The packet can never be processed — free its pooled buffer here or
                    // it leaks (ReaderLoopCloseRaceRegressionTests).
                    owned.buffer.freeNativeMemory()
                    throw e
                }
            }
        } catch (_: ClosedSendChannelException) {
            // Driver closed
        }
    }

    /**
     * Open a new local path, probe it, and arm [pendingMigration]; [drainPathEvents]
     * completes the switch once the peer validates the path. Suspends to open the socket.
     */
    private suspend fun handleMigrate(cmd: QuicheCmd.Migrate) {
        // One translation, not a judgement: each non-Supported capability names exactly one
        // "and never will" outcome, so the caller learns *which* permanent condition applies rather
        // than the single opaque `Unsupported` that used to cover all three.
        val wiring =
            when (migration) {
                MigrationCapability.ServerConnection -> {
                    cmd.result.complete(MigrationResult.Unmoved.Impossible.ServerConnection)
                    return
                }
                MigrationCapability.PolicyForbids -> {
                    cmd.result.complete(MigrationResult.Unmoved.Impossible.PolicyForbids)
                    return
                }
                MigrationCapability.BackendCannotMigrate -> {
                    cmd.result.complete(MigrationResult.Unmoved.Impossible.BackendCannotMigrate)
                    return
                }
                is MigrationCapability.Supported -> migration
            }
        // What the peer's transport parameters (RFC 9000 §18.2) say about moving to a new local address.
        // Exhaustive over three states, with no boolean and no null: "the peer forbids it" and "we have
        // not read the peer's parameters yet" are facts of different kinds — one permanent, one a timing
        // state that resolves on its own — and a nullable Boolean made them share a token.
        when (api.connPeerMigrationPermission(conn)) {
            // Probing would burn a spare DCID only to fail validation.
            PeerMigrationPermission.Forbidden -> {
                cmd.result.complete(MigrationResult.Unmoved.Impossible.PeerForbids)
                return
            }
            // RFC 9000 §9: an endpoint MUST NOT initiate migration before the handshake is confirmed.
            // Retryable, so `Failed` — the very next attempt, once the handshake completes, can succeed.
            PeerMigrationPermission.NotYetNegotiated -> {
                cmd.result.complete(MigrationResult.Unmoved.Failed.HandshakeNotConfirmed)
                return
            }
            PeerMigrationPermission.Permitted -> Unit
        }
        val factory = wiring.channelFactory
        // Refuse a local endpoint this platform cannot bind, rather than opening a socket somewhere
        // else and reporting Succeeded. On Apple `UdpSocket.connect` hands the endpoint to NWConnection
        // and its own comment calls localHost/localPort "advisory", so honouring this request is not
        // possible — and silently substituting a different local address would make the Succeeded value
        // itself a lie. FreshLocalEndpoint is served everywhere and is what automatic migration issues,
        // so this rejects only an explicit, unserviceable ask.
        val namesAnEndpoint = cmd.target !is MigrationTarget.FreshLocalEndpoint
        if (namesAnEndpoint && factory.localEndpointSupport == LocalEndpointSupport.PlatformAssigned) {
            cmd.result.complete(MigrationResult.Unmoved.Failed.EndpointNotSelectable)
            return
        }
        if (pendingMigration != null) {
            cmd.result.complete(MigrationResult.Unmoved.Failed.AlreadyInProgress)
            return
        }
        if (api.connAvailableDcids(conn) <= 0L) {
            cmd.result.complete(MigrationResult.Unmoved.Failed.NoSpareConnectionId)
            return
        }

        // The factory still speaks (host?, port) because that is what every platform's `bind` speaks;
        // the sentinel pair is confined to this one line instead of reaching the public API.
        val requestedHost =
            when (val t = cmd.target) {
                MigrationTarget.FreshLocalEndpoint -> null
                is MigrationTarget.LocalAddress -> t.host
                is MigrationTarget.LocalEndpoint -> t.host
            }
        val requestedPort =
            when (val t = cmd.target) {
                MigrationTarget.FreshLocalEndpoint, is MigrationTarget.LocalAddress -> 0
                is MigrationTarget.LocalEndpoint -> t.port
            }

        val newPath =
            try {
                factory.openPath(requestedHost, requestedPort)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                cmd.result.complete(MigrationResult.Unmoved.Failed.LocalPathUnavailable(e))
                return
            }

        val key = api.decodePathKey(newPath.localSockAddrAddress)
        if (paths.containsKey(key)) {
            // The platform bound the probe to a 4-tuple already in `paths` — with retirement in place
            // and AlreadyInProgress answered above, that can only be the path the connection is living
            // on (a wildcard bind resolving to the active local endpoint after connect). The old
            // unguarded `paths[key] = entry` silently replaced the live entry, orphaning its socket,
            // reader and recv_info with no owner (#395 item 3). Refuse instead: release what the probe
            // acquired (no recv_info exists yet — the guard sits before recvInfoNew on purpose) and
            // report a retryable local failure, since a later bind can land elsewhere.
            releaseUnprobedPath(newPath)
            cmd.result.complete(
                MigrationResult.Unmoved.Failed.LocalPathUnavailable(
                    IllegalStateException(
                        "migration probe bound the connection's current local endpoint (${newPath.localEndpoint})",
                    ),
                ),
            )
            return
        }

        // Probe BEFORE the path entry exists, because the entry cannot be built without the DCID this
        // call returns (see [PathSlot]). The old order — insert, then probe, then read nothing —
        // is what made #447 writable: the sequence number went into `seqScratch` and no failure exit
        // had a value to retire. A rejected probe allocates nothing in quiche (every failure inside
        // `create_path_on_client` returns before `link_dcid_to_path_id`), so there is no recv_info,
        // no map entry and no connection ID to unwind here.
        val probe =
            api.connProbePath(
                conn,
                newPath.localSockAddrAddress,
                newPath.localSockAddrLength,
                wiring.peer.address,
                wiring.peer.length,
            )
        val probed =
            when (probe) {
                is ProbeOutcome.Rejected -> {
                    releaseUnprobedPath(newPath)
                    cmd.result.complete(MigrationResult.Unmoved.Failed.ProbeRejected(probe.code))
                    return
                }

                is ProbeOutcome.Probed -> probe
            }

        val pathRecvInfo =
            api.recvInfoNew(wiring.peer.address, wiring.peer.length, newPath.localSockAddrAddress, newPath.localSockAddrLength)
        val entry =
            PathEntry(
                key = key,
                channel = tapChannel(newPath.channel, key),
                recvInfo = pathRecvInfo,
                localAddr = newPath.localSockAddrAddress,
                localLen = newPath.localSockAddrLength,
                isPrimary = false,
                release = newPath.release,
                // The path now owns the connection ID quiche linked to it. Every way out of here —
                // validated, failed, abandoned, refused — runs through teardownPath, which retires it.
                slot = PathSlot.Probing(probed.dcidSeq),
            )
        paths[key] = entry

        // Every state below names `newPath.localEndpoint` — what the socket BOUND — never the request.
        pendingMigration = PendingMigration(key, newPath.localEndpoint, cmd.result, pathValidationBudget())
        _pathState.value = QuicPathState.Probing(newPath.localEndpoint)
        startReaderLoop(entry) // PATH_CHALLENGE egresses the new socket via flushOutgoing routing
    }

    /**
     * Give back everything [newPath] acquired, for the two exits that abandon it before
     * `quiche_conn_probe_path` ever links a connection ID to it: a bind that collided with the live
     * path's 4-tuple, and a probe quiche refused. Neither has a `recv_info` yet — the collision guard
     * sits before `recvInfoNew` on purpose — and neither owes a retirement, so this is deliberately
     * not [teardownPath]: there is no path entry and no [PathSlot] to leave.
     */
    private fun releaseUnprobedPath(newPath: NewPath) {
        try {
            newPath.channel.close()
        } catch (_: Exception) {
        }
        newPath.release()
    }

    /**
     * The RFC 9000 §8.2.4 abandon timer firing: give up on a path the peer has neither validated nor
     * failed, so `migrate()` returns and the automatic reactor is released to follow the *next*
     * network change.
     *
     * **Order is the correctness argument.** [drainPathEvents] runs FIRST, before anything is
     * declared expired. The deadline and a `Validated` event can come due in the same wake — quiche
     * queues path events and the driver only reads them at [afterCommand], which runs *after* this
     * branch — so expiring first would fail a path the peer actually answered, converting a working
     * migration into a spurious timeout. Draining first, then re-reading [pendingMigration] and
     * re-*measuring* [PendingMigration.isDue], means a validated path wins the race and this only
     * ever fires on genuine silence.
     *
     * Reports [MigrationResult.Unmoved.Failed.PathNotValidated] — the same leaf as an explicit
     * `FailedValidation`, because it is the same fact ("PATH_CHALLENGE unanswered") learned from a
     * timer instead of from quiche. `Failed` and not `Impossible`: nothing about this connection
     * says a later attempt cannot work, and `Impossible` would cancel the reactor for good.
     *
     * The teardown mirrors the [QuichePathEventType.FailedValidation] arm exactly. Without it the
     * probed path's socket, its `recv_info` and its pinned sockaddr stay alive for the connection's
     * life — a per-failed-migration leak on the very code path a flapping network takes repeatedly.
     */
    private fun abandonPathValidationIfDue() {
        if (migrationEnabled) drainPathEvents()
        val pending = pendingMigration ?: return
        if (!pending.isDue()) return
        paths[pending.key]?.let { teardownPath(it) }
        completeMigration(pending, MigrationResult.Unmoved.Failed.PathNotValidated)
    }

    /** Poll and react to quiche path events (validation, failure, path close). Migration-clients only. */
    private fun drainPathEvents() {
        // Only ever called under `if (migrationEnabled)`, and that is now the same fact as
        // `migrationWiring != null` — so this is a re-read of the capability, not a null check on
        // something that might legitimately be absent. Returning is the safe no-op either way.
        val wiring = migrationWiring ?: return
        while (true) {
            val type = api.connPathEventNext(conn, addr(peLocalOut), addr(peLocalLenOut), addr(pePeerOut), addr(pePeerLenOut)) ?: break
            when (type) {
                QuichePathEventType.Validated -> {
                    val key = api.decodePathKey(addr(peLocalOut))
                    val pending = pendingMigration ?: continue
                    if (pending.key != key) continue
                    val entry = paths[key]
                    if (entry == null) {
                        // Internal bookkeeping race: quiche validated a path this driver no longer
                        // tracks. Observably the same as never validating, so it reports the same leaf.
                        completeMigration(pending, MigrationResult.Unmoved.Failed.PathNotValidated)
                        continue
                    }
                    entry.validated()
                    _pathState.value = QuicPathState.Validated(pending.localEndpoint)
                    when (val outcome = api.connMigrate(conn, entry.localAddr, entry.localLen, wiring.peer.address, wiring.peer.length)) {
                        is MigrateOutcome.Migrated -> {
                            // Order matters: `active` moves first so nothing below (or concurrent
                            // teardown-triggered routing) can resolve to the old entry.
                            //
                            // quiche 0.29's `migrate()` on an existing path returns that path's own
                            // `active_dcid_seq` — the id `probe_path` already linked — so this
                            // transition normally carries the same sequence forward and retires
                            // nothing. It is written as a transition anyway because it is the one
                            // place quiche could report a *different* id, and if it ever did, the
                            // probe's would be orphaned: PathEntry.transitionTo retires the displaced
                            // one instead of dropping it on the floor.
                            val previous = active
                            entry.transitionTo(PathSlot.Active(outcome.dcidSeq))
                            active = entry
                            // RFC 9000 §9.5: retire the DCID used on the old path — done by the
                            // teardown itself now, not by a separate call with a separately-tracked
                            // sequence number. This — with the retire-no-relink source patch — is what
                            // clears the old path's `active_dcid_seq` inside quiche, making its slot
                            // evictable; without it the table fills at active_conn_id_limit and the
                            // 4th probe is refused (#395).
                            teardownPath(previous)
                            completeMigration(
                                pending,
                                MigrationResult.Succeeded(pending.localEndpoint),
                                QuicPathState.Migrated(pending.localEndpoint),
                            )
                        }
                        is MigrateOutcome.Rejected -> {
                            // quiche validated the path and then refused to switch to it. Nothing will
                            // ever retry *this* path — `pendingMigration` clears below and the next
                            // migrate() opens a fresh socket — so leaving it in `paths` pins its DCID
                            // and its slot in quiche's path table for the connection's life, exactly
                            // as a failed validation used to (#447). Tear it down, which retires.
                            teardownPath(entry)
                            completeMigration(pending, MigrationResult.Unmoved.Failed.SwitchRejected(outcome.code))
                        }
                    }
                }

                QuichePathEventType.FailedValidation -> {
                    val key = api.decodePathKey(addr(peLocalOut))
                    val pending = pendingMigration
                    if (pending != null && pending.key == key) {
                        paths[key]?.let { teardownPath(it) }
                        completeMigration(pending, MigrationResult.Unmoved.Failed.PathNotValidated)
                    }
                }

                QuichePathEventType.Closed -> {
                    val key = api.decodePathKey(addr(peLocalOut))
                    // Never tear down the path the connection lives on. quiche 0.29 only emits Closed
                    // for paths make_room_for_new_path evicted — which are never active — so this
                    // guard is a backstop, replacing the old `!isPrimary` (post-migration the entry
                    // to protect is `active`, which need not be the primary).
                    paths[key]?.let { if (it !== active) teardownPath(it) }
                }

                QuichePathEventType.New,
                QuichePathEventType.PeerMigrated,
                QuichePathEventType.ReusedSourceConnectionId,
                -> {
                    // Server-side / informational events — no client action for active migration.
                }
            }
        }
    }

    /**
     * Publish the terminal path state for [pending] and hand [result] to the caller parked in
     * `migrate()`.
     *
     * [state] defaults to `QuicPathState.Failed(result)` and is passed explicitly only for the one
     * outcome that is not a failure. That default is what keeps the reported reason and the path state
     * from drifting apart: the state *carries* the result rather than restating it, so there is exactly
     * one place a migration's verdict is written down.
     */
    private fun completeMigration(
        pending: PendingMigration,
        result: MigrationResult.Unmoved,
        state: QuicPathState = QuicPathState.Failed(result),
    ) {
        _pathState.value = state
        pending.result.complete(result)
        pendingMigration = null
    }

    /** [completeMigration]'s success overload — a [MigrationResult.Succeeded] is never a `Failed` state. */
    private fun completeMigration(
        pending: PendingMigration,
        result: MigrationResult.Succeeded,
        state: QuicPathState,
    ) {
        _pathState.value = state
        pending.result.complete(result)
        pendingMigration = null
    }

    /**
     * Cancel a path's reader, close its socket, and — for non-primary paths — free its recv_info and
     * pinned sockaddr. The primary's exemption is an *ownership* fact, not a lifecycle one: its
     * recv_info is the driver-level [recvInfo] freed once in [cleanup], and its sockaddr belongs to
     * the connection setup's [onCleanup] — freeing either here would be a use-after-free later, but
     * its reader and socket retire exactly like any other path's when a migration moves off it
     * (the old `if (entry.isPrimary) return` guard is precisely how the original path could never be
     * released, #395).
     */
    private fun teardownPath(entry: PathEntry) {
        // First, and unconditionally: the path stops holding its destination CID, which retires it
        // (RFC 9000 §9.5). Every caller reaches here — the successful migration's supersede, a
        // FailedValidation, the §8.2.4 abandon timer, a refused switch, a quiche path eviction — so
        // this is the one place a CID can be released, and there is no way to remove a path from
        // `paths` that bypasses it. It runs before the socket closes because it is a quiche call, not
        // an I/O one, and the connection is still live for all of them.
        entry.transitionTo(PathSlot.Abandoned)
        paths.remove(entry.key)
        entry.readerJob?.cancel()
        try {
            entry.channel.close()
        } catch (_: Exception) {
        }
        if (entry.isPrimary) return
        api.recvInfoFree(entry.recvInfo) // free recv_info before the sockaddr it references
        entry.release()
    }

    /**
     * Supply spare source connection IDs to the peer while established. quiche does not auto-issue
     * CIDs, so without this the peer has no spare destination CID to migrate to
     * ([connAvailableDcids] stays 0). Called from [updateState] on every established wake — a no-op
     * costing one [connScidsLeft] read when the peer-granted capacity is full — so retired capacity
     * is replenished (RFC 9000 §5.1.1), not issued exactly once. [MAX_SPARE_SCIDS] bounds the burst
     * per wake, [connScidsLeft] the total outstanding (the peer's active_connection_id_limit).
     *
     * Returns how many were issued. The issued CIDs are not announced one by one any more — the ids
     * are now read back out of quiche by [projectSourceIds], and this count is simply one of the
     * three signals that says the set may have moved.
     */
    private fun issueSpareCids(): Int {
        var count = 0
        while (count < MAX_SPARE_SCIDS && api.connScidsLeft(conn) > 0L) {
            val scid = generateScid(bufferFactory, random) // 20 random bytes, reset for read
            val token = bufferFactory.allocate(STATELESS_RESET_TOKEN_LEN)
            repeat(STATELESS_RESET_TOKEN_LEN) { token.writeByte(random.nextInt(256).toByte()) }
            token.resetForRead()
            val rc =
                api.connNewScid(
                    conn,
                    scid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_MAX_CONN_ID_LEN,
                    token.nativeMemoryAccess!!.nativeAddress.toLong(),
                    true,
                    addr(seqScratch),
                )
            scid.freeNativeMemory()
            token.freeNativeMemory()
            if (rc < 0) break
            count++
        }
        return count
    }

    /**
     * Drain the source CIDs the peer has retired, returning how many quiche yielded.
     *
     * `quiche_conn_retired_scid_iter` **drains** — an id this never collects stays queued inside
     * quiche forever — so this must keep running even though the routing table no longer learns
     * retirements from it. Since #449 the ids themselves are not what the server needs: it takes the
     * live set from [projectSourceIds] instead, and a CID the peer retired is already absent from
     * `quiche_conn_source_ids` (quiche removes it from `ids.scids` in the same call that queues it
     * here). What this call still provides is the *fact* that the set moved, which is why the count
     * is returned.
     *
     * Costs one [QuicheApi.connRetiredScids] read per established wake, alongside [issueSpareCids]'s
     * `connScidsLeft`; everything else runs only when the peer has actually retired something, which
     * happens a handful of times per migration.
     *
     * The count is read first and sizes the drain, and both run here on the driver coroutine — the
     * only place allowed to touch the connection — so nothing can retire an id in between.
     */
    private fun drainRetiredScids(): Int {
        val count = api.connRetiredScids(conn)
        if (count <= 0) return 0
        val slots = bufferFactory.allocate(count * RETIRED_SCID_SLOT_BYTES)
        return try {
            val yielded = api.connDrainRetiredScids(conn, addr(slots), count)
            // A backend that yields more than it was sized for has lost the excess — impossible while
            // the read above and this call share the driver coroutine, so it is reported rather than
            // handled.
            if (yielded > count) {
                recorder?.error(RetiredScidOverflow(yielded, count))
            }
            minOf(yielded, count)
        } finally {
            slots.freeNativeMemory()
        }
    }

    /**
     * How many source CIDs the last [projectSourceIds] published. Compared against
     * [QuicheApi.connActiveScids] on every established wake so the projection is **self-correcting**:
     * if the two disagree for any reason at all — a retirement quiche performed on its own via
     * `retire_prior_to`, a signal this driver did not predict, a projection that never landed — the
     * next wake notices and re-projects. That is the difference between a projection and the ledger
     * it replaces: the ledger could only ever be as right as the events it was fed.
     *
     * Starts at 0, which is also what a [QuicheApi] test double reports, so a backend that has not
     * bound the readback projects nothing rather than projecting an empty set over live routes.
     */
    private var projectedScidCount = 0

    /**
     * Publish quiche's current source-connection-id set to [onSourceIds], if it may have changed.
     *
     * [setMayHaveMoved] carries the two changes this driver *caused* on this wake (it issued a CID,
     * or the peer retired one); the count comparison catches everything else. In the steady state
     * neither fires and this is a single `quiche_conn_active_scids` integer read.
     *
     * Server-only in practice: a client leaves [onSourceIds] null because it demuxes by per-path
     * socket and has no DCID map to keep in step.
     */
    private fun projectSourceIds(setMayHaveMoved: Boolean) {
        val sink = onSourceIds ?: return
        val active = api.connActiveScids(conn)
        if (!setMayHaveMoved && active == projectedScidCount) return
        if (active <= 0) {
            // Nothing to route, or a backend with no readback bound (the interface default answers 0).
            // Either way, publishing an empty set over the accept-time routes would unroute a live
            // connection, so the projection stays silent and the count stays where it was.
            return
        }
        val slots = bufferFactory.allocate(active * RETIRED_SCID_SLOT_BYTES)
        try {
            val yielded = api.connReadSourceIds(conn, addr(slots), active)
            if (yielded > active) {
                // Sized from connActiveScids one line earlier, on this same coroutine, so this cannot
                // happen — reported rather than handled, and the projection is abandoned rather than
                // published, because a partial set would unroute the ids that did not fit.
                recorder?.error(RetiredScidOverflow(yielded, active))
                return
            }
            if (yielded <= 0) {
                // connActiveScids says there is a set and connReadSourceIds yielded none: a backend
                // with only half the readback bound (the other half answering the QuicheApi default).
                // Publishing that would unroute every id this connection has — the exact opposite of
                // what a projection is for — so report it and leave the map alone.
                recorder?.error(RetiredScidOverflow(yielded, active))
                return
            }
            // Unlike the retired-id drain this is a plain read, so a sink that snapshots the bytes
            // can be handed the same scratch buffer slot by slot; nothing is lost by repeating it.
            sink.replaceRoutes(slots, yielded)
            projectedScidCount = yielded
        } finally {
            slots.freeNativeMemory()
        }
    }

    private fun cleanup() {
        // Latch identity while the conn is alive and we are on its loop: after the api.*Free calls
        // below, closeAttribution() from a caller thread must use this snapshot instead of
        // dereferencing freed quiche memory (see latchedIdentity).
        latchedIdentity = QuicConnectionIdentity(session = sessionId, wire = wireConnectionId)
        commands.close()

        while (true) {
            val cmd = commands.tryReceive().getOrNull() ?: break
            failCommand(cmd)
        }

        // A migration still in flight when the connection dies never completes — fail it.
        pendingMigration?.result?.complete(MigrationResult.Unmoved.Impossible.ConnectionClosed)
        pendingMigration = null

        for (slot in streams.values) {
            slot.dataSignal.close()
            // Unblock any writer parked on a reopened-window signal — without this it would hang until
            // its withTimeout fired. streamWrite maps the resulting closed-channel to QuicCloseException.
            slot.writableSignal.close()
        }
        streams.clear()
        // Unblock any datagram receiver/sender parked on these signals — the closed-channel unwinds
        // them to ConnectionClosed / QuicCloseException (see DriverDatagramAdapter).
        dgramSignal.close()
        dgramWritableSignal.close()
        // Trace capture: one final stats snapshot while the conn handle is still alive, so every
        // recorded session ends with the terminal loss/RTT/byte counters (STATS) even if no timer
        // wake happened (e.g. a pure event-cascade virtual-time run).
        recorder?.let { r -> api.connPathStats(conn, 0L)?.let { r.stats(it) } }
        api.connFree(conn)
        // Tear down any non-primary migration paths: cancel reader, close socket, free
        // recv_info before its sockaddr. Iterate a copy — teardown logic mutates `paths`.
        for (entry in paths.values.toList()) {
            if (entry.isPrimary) continue
            entry.readerJob?.cancel()
            try {
                entry.channel.close()
            } catch (_: Exception) {
            }
            api.recvInfoFree(entry.recvInfo)
            entry.release()
        }
        paths.clear()
        api.recvInfoFree(recvInfo)
        api.sendInfoFree(sendInfo)
        udpSendBuf.freeNativeMemory()
        seqScratch.freeNativeMemory()
        peLocalOut?.freeNativeMemory()
        peLocalLenOut?.freeNativeMemory()
        pePeerOut?.freeNativeMemory()
        pePeerLenOut?.freeNativeMemory()
        // commands.tryReceive() drain above freed any pending RecvPackets
        // back to the pool. Late releases from an in-flight udpReaderLoop
        // iteration are benign — they repopulate the pool, which is GC'd
        // with the driver. Same for stream-read buffers still owned by
        // consumers: their freeNativeMemory() after this clear repopulates
        // a pool that dies with the driver.
        recvBufPool.clear()
        streamReadPool.clear()
        incomingStreams.close()
        // Released last — quiche may have dereferenced recvInfo.from/to inside
        // any of the api.*Free() calls above. Safe to release the underlying
        // sockaddr storage only after the conn/recvInfo handles are gone.
        onCleanup()
        // Under a virtual clock the syncing decorator pinned libquiche's per-thread virtual time on
        // this (potentially pooled) OS thread; release it so no later work on the same thread inherits
        // a stale virtual instant. No-op under a real clock (nothing was ever pinned).
        if (clock.quicheTime() is DriverTime.Virtual) api.clearThreadVirtualTime()
    }

    private fun failCommand(cmd: QuicheCmd) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> {
                cmd.buf.freeNativeMemory()
                // Dropped without connRecv — still release the server's in-flight ref.
                (cmd.source as? PacketSource.FromServerSocket)?.onConsumed?.invoke()
            }
            is QuicheCmd.OpenStream ->
                cmd.result.completeExceptionally(
                    QuicCloseException(closeReasonOr(QuicError.NoError), "connection closed", attribution = closeAttribution()),
                )
            is QuicheCmd.StreamRecv -> cmd.result.complete(StreamRecvResult.ConnectionGone)
            is QuicheCmd.StreamSend -> cmd.result.complete(StreamSendResult(-1))
            // The stream/connection is gone; the shutdown frame won't go out, which is fine — the
            // peer already sees the connection closing. Complete with a benign 0 (no-op).
            is QuicheCmd.StreamShutdown -> cmd.result.complete(0)
            // Datagrams: receive → ConnectionGone maps to ConnectionClosed; send → -1 parks on the
            // (now-closed) dgramWritableSignal, which throws QuicCloseException. Mirrors the stream
            // cases above.
            is QuicheCmd.DgramRecv -> cmd.result.complete(StreamRecvResult.ConnectionGone)
            is QuicheCmd.DgramSend -> cmd.result.complete(-1)
            // Connection gone before the cert could be read — report "no certificate" (0); the verifier
            // turns that into a handshake failure, which is the right outcome for a torn-down connection.
            is QuicheCmd.PeerCert -> cmd.result.complete(0)
            // Connection gone — no quiche handles to read; an all-null snapshot is the typed "no stats".
            is QuicheCmd.Stats -> cmd.result.complete(QuicStatsSnapshot(null, null))
            // Nothing negotiated can be read from a freed handle, which is exactly what this case means.
            is QuicheCmd.PeerTransportParamsRead -> cmd.result.complete(PeerTransportParams.NotYetNegotiated)
            is QuicheCmd.SourceIdsRead -> cmd.result.complete(emptyList())
            is QuicheCmd.Close -> cmd.result.complete(Unit)
            is QuicheCmd.Migrate -> cmd.result.complete(MigrationResult.Unmoved.Impossible.ConnectionClosed)
        }
    }

    /**
     * A backend call threw with [cmd] already dequeued — so [cleanup]'s teardown drain can never
     * reach it, and without this its deferred would never complete. That is not a theoretical gap:
     * `streamRead`/`streamWrite`/the datagram adapter end with a NonCancellable `join()` on exactly
     * that deferred (the barrier that keeps a caller from freeing a buffer whose raw address a
     * queued command still carries), so an uncompleted deferred is a permanent, uncancellable hang.
     * The PeerCert arm has guarded itself this way since it landed (see its inline try/catch);
     * every arm gets the same protection at the dispatch site. The throw still unwinds [run]
     * afterwards, so the connection tears down through [cleanup] exactly as before.
     *
     * `completeExceptionally` on an already-completed deferred is a no-op, so arms that completed
     * before throwing (e.g. PeerCert's own catch) are unaffected. RecvPacket carries no deferred —
     * it gets the same driver-owned-buffer release [failCommand] gives it; the pooled free is
     * idempotent, so an arm that freed before throwing cannot double-release.
     */
    private fun failCommandExceptionally(
        cmd: QuicheCmd,
        cause: Throwable,
    ) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> {
                cmd.buf.freeNativeMemory()
                (cmd.source as? PacketSource.FromServerSocket)?.onConsumed?.invoke()
            }
            is QuicheCmd.OpenStream -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.StreamRecv -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.StreamSend -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.StreamShutdown -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.DgramRecv -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.DgramSend -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.PeerCert -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.Stats -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.PeerTransportParamsRead -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.SourceIdsRead -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.Close -> cmd.result.completeExceptionally(cause)
            is QuicheCmd.Migrate -> cmd.result.completeExceptionally(cause)
        }
    }

    /**
     * Read a [QuicStatsSnapshot] on the driver loop (quiche is single-threaded — never off-loop).
     * Members are `null` on backends without the stats FFI bound, and the whole snapshot is
     * all-null once the connection is torn down. Suspends until the driver processes the command.
     */
    suspend fun stats(): QuicStatsSnapshot =
        try {
            val deferred = CompletableDeferred<QuicStatsSnapshot>()
            commands.send(QuicheCmd.Stats(deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            QuicStatsSnapshot(null, null)
        }

    /**
     * The source connection IDs quiche currently considers active, newest-first as the iterator
     * yields them. Empty once the connection is gone — the same honest answer
     * [peerTransportParams] gives for a freed handle.
     *
     * This is the read-back half of the CID API (`quiche_conn_source_ids`), which this project
     * issued and retired against for years without ever asking quiche what the live set IS. Until it
     * existed there was no second opinion to reconcile our own routing table against, so a
     * divergence could only ever be discovered downstream — as a dropped packet (#437) or a path
     * slot pinned forever (#395, #447).
     */
    suspend fun sourceIds(): List<ByteArray> =
        try {
            val deferred = CompletableDeferred<List<ByteArray>>()
            commands.send(QuicheCmd.SourceIdsRead(deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            emptyList()
        }

    /**
     * Count-then-read on the driver coroutine, sized from [QuicheApi.connActiveScids]. Mirrors
     * [drainRetiredScids]'s buffer discipline; unlike it, `quiche_conn_source_ids` does not drain, so
     * a short read loses nothing permanent — it is still reported rather than clamped, because a
     * count and a read that disagree mean the confinement assumption above has broken.
     */
    private fun readSourceIds(): List<ByteArray> {
        val count = api.connActiveScids(conn)
        if (count <= 0) return emptyList()
        val slots = bufferFactory.allocate(count * RETIRED_SCID_SLOT_BYTES)
        try {
            val yielded = api.connReadSourceIds(conn, addr(slots), count)
            if (yielded > count) recorder?.error(RetiredScidOverflow(yielded, count))
            return (0 until minOf(yielded, count)).mapNotNull { i ->
                slots.position(i * RETIRED_SCID_SLOT_BYTES)
                val len = slots.readByte().toInt() and 0xFF
                if (len in 1..QUIC_MAX_CONN_ID_LEN) ByteArray(len) { slots.readByte() } else null
            }
        } finally {
            slots.freeNativeMemory()
        }
    }

    /**
     * Read the peer's transport parameters (RFC 9000 §18) on the driver loop — quiche is
     * single-threaded, never off-loop. [PeerTransportParams.NotYetNegotiated] until the handshake has
     * processed them, and once the connection is torn down.
     *
     * Exists for `PeerTransportParamsLayoutTestSuite`, which asserts the *neighbours* of
     * `disable_active_migration` against the values this connection configured. That is the only thing
     * that catches the quiche ABI defect this module patches around
     * (`patchQuicheTransportParamsRepr`): the flag itself is a silent kill switch, so a wrong read
     * turns active migration off with no error anywhere, and `sizeof` agrees either way.
     */
    suspend fun peerTransportParams(): PeerTransportParams =
        try {
            val deferred = CompletableDeferred<PeerTransportParams>()
            commands.send(QuicheCmd.PeerTransportParamsRead(deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            PeerTransportParams.NotYetNegotiated
        }

    suspend fun destroy() {
        commands.close()
        driverJob?.join()
    }

    companion object {
        const val MAX_DATAGRAM_SIZE = 1350

        /**
         * How many consecutive receive failures a path's reader tolerates before it stops (#396).
         *
         * The loops previously retried a failed `receive` with `continue` — no delay, no bound. A
         * *persistent* socket error makes `selector.select()` return immediately every time, so the
         * reader span the dispatcher at full tilt for the rest of the connection's life; combined with
         * a path that is never retired (#395) it was still running 101 minutes later. Android's netd
         * `SOCK_DESTROY` produces exactly that when a network goes away under a live socket.
         *
         * A bound plus backoff is deliberately generous: a genuinely transient error clears within a
         * few milliseconds, so ~2s of retrying costs nothing, while a dead socket stops instead of
         * burning a core indefinitely. Escalating a stopped reader into a path teardown belongs to
         * #395, which owns path lifecycle; this only stops the spin and the per-iteration leak.
         */
        private const val MAX_CONSECUTIVE_RECEIVE_FAILURES = 20

        /** Backoff ceiling for [receiveRetryBackoff]. */
        private val RECEIVE_RETRY_BACKOFF_CAP = 100.milliseconds

        /**
         * Exponential backoff for consecutive receive failures: 1ms, 2ms, 4ms … capped at
         * [RECEIVE_RETRY_BACKOFF_CAP]. Shifting is bounded before it can overflow the exponent.
         */
        private fun receiveRetryBackoff(consecutiveFailures: Int): Duration {
            val shift = (consecutiveFailures - 1).coerceIn(0, 30)
            val millis = 1L shl shift
            return if (millis >= RECEIVE_RETRY_BACKOFF_CAP.inWholeMilliseconds) {
                RECEIVE_RETRY_BACKOFF_CAP
            } else {
                millis.milliseconds
            }
        }

        /** RFC 9002 §6.1.2 `kGranularity` — the timer-granularity floor inside the PTO formula. */
        private val K_GRANULARITY = 1.milliseconds

        /** RFC 9002 §6.2.2 `kInitialRtt` — the RTT an endpoint assumes before it has a single sample. */
        private val K_INITIAL_RTT = 333.milliseconds

        /**
         * The PTO for a path with no RTT samples: `kInitialRtt` as the smoothed RTT, `kInitialRtt / 2`
         * as rttvar (RFC 9002 §5.1 sets rttvar to half the RTT on the first sample) and no
         * `max_ack_delay` (RFC 9002 §6.2.1 excludes it until the handshake is confirmed) — 999 ms.
         *
         * It is also the floor of [pathValidationBudget]: RFC 9000 §8.2.4 takes the *larger* of the two
         * PTOs, so a very fast current path can never shrink the abandon timer below ~3 s.
         */
        private val INITIAL_PTO = K_INITIAL_RTT + maxOf((K_INITIAL_RTT / 2) * 4, K_GRANULARITY)

        /** RFC 9000 §8.2.4: "three times the larger of the current PTO or the PTO for the new path". */
        private const val PATH_VALIDATION_PTO_MULTIPLIER = 3

        /**
         * Scratch capacity for the connection-id readers. A CID is at most 20 bytes (RFC 9000 §17.2) and
         * quiche's trace id is its hex rendering, so 64 clears both with room to spare — and the
         * snprintf-style contract means an over-long value reports its length rather than truncating
         * silently.
         */
        private const val CONN_ID_TEXT_CAPACITY = 64

        private val HEX = "0123456789abcdef".toCharArray()

        /** Max ALPN protocol identifier length (RFC 7301 — 1-byte length prefix, so ≤ 255). */
        private const val MAX_ALPN_LEN = 255

        /** maxPoolSize for a per-connection datagram recv pool — ~87 KB cached (64 × 1350). */
        private const val RECV_BUF_POOL_SIZE = 64

        /**
         * Build a per-connection datagram recv [BufferPool] from a **leaf** [factory]. One construction
         * shared by the driver's default and the client build sites, which create the pool up front and
         * inject the SAME instance into both the driver ([recvBufPool]) and the `:socket-udp` receive
         * channel (`UdpSocket.connect(bufferFactory = pool)`) so datagrams land in it with no copy. Never
         * pass an already-pooled factory (the `80575c1` double-wrap regression).
         */
        internal fun newRecvBufPool(factory: BufferFactory): BufferPool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = RECV_BUF_POOL_SIZE,
                defaultBufferSize = MAX_DATAGRAM_SIZE,
                factory = factory,
            )

        /**
         * Default per-read buffer size for QUIC stream reads — [QuicheStreamByteStream]'s default
         * `bufferSize` and [streamReadPool]'s buffer size, kept equal so every default-sized read
         * is a pool hit.
         */
        const val STREAM_READ_BUFFER_SIZE = 65536

        /** Size of a `sockaddr_storage` — the out-buffers quiche fills for path events. */
        const val SOCKADDR_STORAGE_SIZE = 128

        /** QUIC stateless-reset token length (RFC 9000 §10.3) — fixed 16 bytes. */
        const val STATELESS_RESET_TOKEN_LEN = 16

        /** Cap on spare source CIDs issued per connection (bounded further by connScidsLeft). */
        const val MAX_SPARE_SCIDS = 3

        /**
         * `QUICHE_ERR_DONE` (RFC-agnostic quiche sentinel). On a stream *write* it means the stream is
         * flow-control blocked with no capacity right now — back-pressure, not failure — and the caller
         * should retry once the peer's `MAX_STREAM_DATA` / `MAX_DATA` reopens the window. (The read path
         * already maps it to [StreamRecvResult.Done].)
         */
        const val QUICHE_ERR_DONE = -1

        /**
         * `QUICHE_ERR_STREAM_STOPPED` (quiche.h). The peer sent STOP_SENDING (RFC 9000 §19.5): it no
         * longer wants what we are writing to THIS stream. A stream-level event — the connection is
         * healthy — so a stream *write* hitting it raises [QuicStreamException], not [QuicCloseException].
         */
        const val QUICHE_ERR_STREAM_STOPPED = -15

        /**
         * `QUICHE_ERR_STREAM_RESET` (quiche.h). The peer sent RESET_STREAM (RFC 9000 §19.4) on THIS
         * stream. Like [QUICHE_ERR_STREAM_STOPPED], stream-scoped — the connection survives.
         */
        const val QUICHE_ERR_STREAM_RESET = -16

        /**
         * `QUICHE_ERR_INVALID_STREAM_STATE` (quiche.h) — one of only two `stream_recv` codes
         * reachable on a live connection; previously had no Kotlin name anywhere.
         */
        const val QUICHE_ERR_INVALID_STREAM_STATE = -7

        /**
         * `QUICHE_ERR_STREAM_LIMIT` (quiche.h): the peer's `initial_max_streams` is reached, so quiche
         * will not create another stream of this kind. Surfaced by the materialising send in
         * [QuicheCmd.OpenStream] (#423) rather than being discovered later by the first real write.
         */
        const val QUICHE_ERR_STREAM_LIMIT = -12
    }
}

/**
 * [QuicheStreamAdapter] that submits commands to the [QuicheDriver].
 * Uses [StreamSlot.dataSignal] for reactive reads — no polling.
 */
class DriverStreamAdapter(
    private val driver: QuicheDriver,
    private val slot: StreamSlot,
) : QuicheStreamAdapter {
    /**
     * The next chunk the driver drained out of quiche at teardown, or null when there is none.
     *
     * Consulted **before** every terminal verdict: bytes the transport already accepted outrank both
     * the FIN (RFC 9000 §2.4 — a final size marks where the data ends, it does not discard it) and the
     * connection's death (§10.2). Returning End while this queue is non-empty is exactly the #318
     * data loss. Ownership of the buffer transfers to the caller, like the [streamRead] data path.
     */
    private fun pendingData(): ReadResult.Data? =
        slot.pendingData
            .tryReceive()
            .getOrNull()
            ?.let { ReadResult.Data(it) }

    /**
     * Release the teardown-drained chunks this stream will never deliver. Called when the read side is
     * gone for good ([QuicheStreamByteStream.close] / [QuicheStreamByteStream.reset]) — after that no
     * `read()` can hand them out, so holding them would leak a pooled/native buffer per undelivered chunk.
     */
    override fun releaseUndeliveredReads() {
        while (true) {
            val buffer = slot.pendingData.tryReceive().getOrNull() ?: return
            // Recorded before freeing: releasing here is CORRECT (no read() can hand these out any
            // more), and they are still bytes quiche accepted that the application never saw. Only
            // the second fact explains a stream that ends short, so the trace has to carry it.
            driver.recorder?.streamLoss(slot.id.id, buffer.remaining(), StreamLossCause.ReaderGone)
            buffer.freeIfNeeded()
        }
    }

    /**
     * Rescue a chunk the driver delivered into a [streamRead] that has already unwound.
     *
     * `commands` is UNLIMITED, so the `StreamRecv` is queued before a `withTimeout` deadline (or an
     * external cancel) can reach us; the driver then processes it regardless of what happened to the
     * caller, and by the time [streamRead]'s non-cancellable join returns quiche may **already** have
     * answered [StreamRecvResult.Data]. quiche has by then advanced the stream's receive offset and
     * credited flow control, so the peer will never resend those bytes: freeing the buffer at that point
     * — what the cancellation path used to do unconditionally — punches a permanent hole in the stream.
     * A FIN riding on the same chunk was lost with it, because `slot.end` is latched inside the
     * `when` the cancellation skipped, after which no `read()` can ever report a clean end.
     *
     * That is issue #393: on a 124-minute on-device Android handoff run the stream died on exactly the
     * two migrations that a read timeout preceded (8.6s and 6.0s before), and stayed dead for the
     * remaining 101 minutes while the connection itself kept exchanging keepalives. The migration only
     * makes read timeouts likely; the timeout is what loses the data.
     *
     * The salvage is the cancellation-edge mirror of [drainStreamIntoSlot]'s teardown-edge drain (issue
     * #318) and keeps its conventions: bytes go to [StreamSlot.pendingData] so the next `read()` hands
     * them out ahead of any terminal verdict, the FIN is published *after* the chunk is queued so an
     * `End` verdict can never race ahead of the data it is supposed to follow, and whatever is left
     * undelivered is released by [releaseUndeliveredReads].
     *
     * @return true when ownership of [buffer] moved to the slot — the caller must **not** release it.
     */
    private fun salvageCancelledRecv(
        completed: CompletableDeferred<StreamRecvResult>,
        buffer: PlatformBuffer,
    ): Boolean {
        // A deferred that failed or was cancelled carries no result, and asking one for its value would
        // rethrow inside a `finally` — masking the timeout the caller is already unwinding with.
        if (completed.isCancelled) return false
        val answered = completed.getCompleted()
        // A Reset answered into a read that already gave up must still latch: quiche collects the
        // stream on delivery, so nothing re-answers it — dropping it here would leave the next read
        // parked until its own deadline and the peer's abort lost for good (#398, the #393 shape).
        if (answered is StreamRecvResult.Reset) {
            if (slot.end == StreamEnd.Open) slot.end = StreamEnd.Reset(answered.applicationErrorCode)
            return false
        }
        // Done / Error deliver nothing: quiche advanced no offset, so there is nothing to salvage.
        val result = answered as? StreamRecvResult.Data ?: return false
        var queued = false
        if (result.bytesRead > 0) {
            buffer.position(result.bytesRead)
            buffer.resetForRead()
            // UNLIMITED and never closed, so the send cannot fail; on the impossible branch report "not
            // transferred" so the caller frees the buffer rather than leaking it.
            queued = slot.pendingData.trySend(buffer).isSuccess
            if (!queued) {
                // The caller frees the buffer on this branch, so these bytes are gone. quiche already
                // advanced the receive offset for them — this is the #393 shape, named at the moment
                // it happens instead of being inferred later from a short stream.
                driver.recorder?.streamLoss(slot.id.id, result.bytesRead, StreamLossCause.SalvageUnclaimed)
            }
        }
        if (result.fin && slot.end == StreamEnd.Open) slot.end = StreamEnd.Fin
        return queued
    }

    override suspend fun streamRead(
        streamId: QuicStreamId,
        bufferFactory: BufferFactory,
        bufferSize: Int,
        timeout: Duration,
    ): ReadResult {
        // Before allocating anything: a chunk drained at teardown is already ours to hand back.
        pendingData()?.let { return it }
        val buffer = bufferFactory.allocate(bufferSize)
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong()

        // A StreamRecv we enqueued but the driver has not yet completed. While this is set, the driver may
        // still be about to WRITE received bytes into `addr` inside connStreamRecv. The command channel is
        // UNLIMITED so `commands.send` never suspends — by the time a timeout or external cancellation can
        // unwind us, the command is already queued. If we let `buffer` be released here (freed below, or for
        // a heap/GC-backed buffer simply dropped so its Cleaner reclaims the native memory) before the
        // driver finishes, quiche writes into freed memory and corrupts the native heap (the rare
        // "SIGSEGV in malloc" crash). So on every exit we first wait — non-cancellably — for any in-flight
        // StreamRecv to complete, and only then decide the buffer's fate: whatever quiche delivered into
        // it while we were unwinding goes to the slot ([salvageCancelledRecv]), and only an empty buffer
        // is released.
        var inFlight: CompletableDeferred<StreamRecvResult>? = null
        var transferred = false
        // The chunk this read produced, kept for the same reason [pendingTaken] is: `withTimeout` can
        // discard the value its block returns when the deadline lands in the gap between producing it and
        // delivering it. For a chunk taken off [StreamSlot.pendingData] that gap was closed by #414. This
        // is the other edge — bytes quiche delivered into OUR buffer — and it was still open: `transferred`
        // is set on the line before the return, so a deadline that won the race left the chunk owned by
        // nobody. The caller never saw it, and the finally below skipped [salvageCancelledRecv] precisely
        // BECAUSE `transferred` said the caller had it, so not even a STREAM_LOSS was recorded. One
        // 64-byte chunk vanished from a healthy stream, and the byte-continuity ledger reported it as the
        // peer sending the wrong bytes (#433).
        var delivered: ReadResult.Data? = null
        // A chunk already taken out of slot.pendingData but not yet handed to the caller.
        //
        // pendingData() is destructive: the buffer leaves the queue and its ownership moves to us. If the
        // withTimeout deadline beats our return, withTimeout throws and the produced value is dropped —
        // the chunk is then unreachable by any later read() and by releaseUndeliveredReads(), so it is
        // both silent stream data loss and a leak. (withTimeout does not merely check the deadline at
        // suspension points: its TimeoutCoroutine is cancelled by a scheduled task, and a block that
        // completes at the same instant loses the race and its result — a non-suspending
        // `take(); return` sequence is not safe from it.) Recording the take is what lets the catch
        // below hand those bytes over anyway, and the finally put them back if it cannot. (#414)
        var pendingTaken: ReadResult.Data? = null

        // pendingData(), but remembering what it took. Every destructive take inside withTimeout goes
        // through this; the one before the try is outside the deadline and does not need it.
        fun takePending(): ReadResult.Data? = pendingData()?.also { pendingTaken = it }
        try {
            val result =
                withTimeout(timeout) {
                    // The FIN (or Reset) may have arrived coalesced with the last data chunk on a previous
                    // read() (which returned that Data and latched slot.end here). quiche has already
                    // delivered it, so there is no further data and no readable-signal coming — return now
                    // instead of issuing a StreamRecv that returns Done and parking on dataSignal forever.
                    // A teardown drain that queued the last chunk *and* latched slot.end lands here too:
                    // the queued bytes come first, the terminal verdict only once they are gone.
                    when (slot.end) {
                        StreamEnd.Open -> {}
                        StreamEnd.Fin -> return@withTimeout takePending() ?: ReadResult.End
                        // The peer's abort outlives the read that observed it: quiche collects the stream
                        // once the reset is delivered, so the slot's latched verdict — not a fresh
                        // stream_recv — is the only truthful answer a later read can give (#398).
                        is StreamEnd.Reset -> return@withTimeout takePending() ?: ReadResult.Reset
                    }
                    while (true) {
                        val deferred = CompletableDeferred<StreamRecvResult>()
                        // trySend, not send: on the UNLIMITED command channel it always succeeds unless
                        // the channel is closed (getOrThrow then rethrows the close cause, exactly like
                        // send). Unlike send it is not a suspend call, so there is no state in which the
                        // command is buffered yet the call throws CancellationException — the gap that
                        // would leave `inFlight` unset and let the finally below skip its join while the
                        // driver still holds this buffer's raw address (#401 hunt).
                        driver.commands.trySend(QuicheCmd.StreamRecv(streamId.id, addr, bufferSize, deferred)).getOrThrow()
                        // Mark in-flight only AFTER a successful enqueue: if trySend threw (channel closed)
                        // the command never reached the driver, so there is nothing to join (joining it would hang).
                        inFlight = deferred
                        val result = deferred.await()
                        inFlight = null
                        when (result) {
                            is StreamRecvResult.Data -> {
                                // Record the FIN whether or not this chunk also carried data — a coalesced
                                // FIN (bytes > 0 && fin) is otherwise dropped, wedging the next read().
                                // Guarded so this can never downgrade an already-latched terminal state.
                                if (result.fin && slot.end == StreamEnd.Open) slot.end = StreamEnd.Fin
                                if (result.bytesRead > 0) {
                                    buffer.position(result.bytesRead)
                                    buffer.resetForRead()
                                    // Ownership transfers to the caller — do not release in the finally.
                                    transferred = true
                                    delivered = ReadResult.Data(buffer)
                                    return@withTimeout delivered
                                }
                                // A pure FIN. Drain the slot first: the teardown drain can queue bytes in
                                // the same driver wake that answers this FIN, and End must never overtake
                                // them (the #318 shape — this was the one terminal arm that skipped it).
                                if (result.fin) {
                                    return@withTimeout takePending() ?: ReadResult.End
                                }
                                // 0 bytes and no FIN is not an end of anything ("0 implies FIN" was an
                                // unenforced assumption): wait for the data signal like a Done and retry.
                                takePending()?.let { return@withTimeout it }
                                slot.dataSignal.receive()
                                continue
                            }
                            is StreamRecvResult.Done -> {
                                // The teardown drain may have emptied quiche into the slot between our
                                // enqueue and now — take that before any terminal verdict.
                                takePending()?.let { return@withTimeout it }
                                // Defensive: if the FIN (or Reset) was consumed earlier (coalesced with
                                // data), no signal is coming — end now rather than park forever.
                                when (slot.end) {
                                    StreamEnd.Open -> {}
                                    StreamEnd.Fin -> return@withTimeout ReadResult.End
                                    is StreamEnd.Reset -> return@withTimeout ReadResult.Reset
                                }
                                slot.dataSignal.receive()
                                continue
                            }
                            is StreamRecvResult.Error -> {
                                // Bytes quiche already handed over outrank the failure, exactly as they
                                // outrank a FIN (RFC 9000 §2.4) and the connection's death (§10.2) — the
                                // #318/#393 ordering rule. Only once the slot is dry does the failure surface.
                                takePending()?.let { return@withTimeout it }
                                // ...and it surfaces AS a failure. Mapping this onto ReadResult.End told every
                                // caller the peer had finished politely, which is a contract — stop reading,
                                // release the stream — and the wrong response to an error. It is also
                                // undiagnosable: 30 minutes of `End` in the #393 device recording could not say
                                // whether the peer closed the stream or quiche was failing every read, and those
                                // have opposite fixes. Throwing matches what streamWrite already does for a
                                // stream-scoped failure; the complete fix is a typed failure in the read RESULT,
                                // which needs buffer's ReadResult to gain a case (DitchOoM/buffer#376, v7). #421.
                                throw QuicStreamReadException(
                                    streamId = streamId.id,
                                    error =
                                        if (result.code == QuicheDriver.QUICHE_ERR_INVALID_STREAM_STATE) {
                                            QuicStreamReadError.InvalidStreamState
                                        } else {
                                            QuicStreamReadError.Quiche(result.code)
                                        },
                                    message = "QUIC stream ${streamId.id} read failed (quiche code ${result.code})",
                                )
                            }
                            is StreamRecvResult.Reset -> {
                                // The peer sent RESET_STREAM. Latch the abort (with its application error
                                // code) so every later read reports it — quiche collects the stream now, so
                                // nothing re-delivers this — and report Reset, never End: an abnormal,
                                // code-carrying abort is not the peer finishing politely (#398). Bytes the
                                // transport already accepted still outrank the verdict (the #318/#393 rule).
                                if (slot.end == StreamEnd.Open) {
                                    slot.end = StreamEnd.Reset(result.applicationErrorCode)
                                }
                                return@withTimeout takePending() ?: ReadResult.Reset
                            }
                            is StreamRecvResult.ConnectionGone -> {
                                // Includes the teardown sentinel from failCommand: the connection went away
                                // while this StreamRecv was queued, so whatever quiche still held for us was
                                // drained into the slot on the way out — deliver it before ending the stream.
                                return@withTimeout takePending() ?: ReadResult.End
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    ReadResult.End
                }
            // Handed to the caller — the finally must not put it back.
            pendingTaken = null
            return result
        } catch (e: TimeoutCancellationException) {
            // Bytes the transport already accepted outrank the deadline, exactly as they outrank the FIN
            // (RFC 9000 §2.4 — a final size marks where the data ends, it does not discard it) and the
            // connection's death (§10.2). quiche has already advanced this stream's receive offset and
            // credited flow control for these bytes, so the peer will never resend them: throwing here
            // would punch a permanent hole in the stream — #393's failure mode reached through the
            // delivery edge instead of the cancellation edge. The read did not time out; it had an
            // answer, and the answer arrived before the deadline did.
            pendingTaken?.let {
                pendingTaken = null
                return it
            }
            // The same rule on the other edge: quiche wrote these bytes into our buffer and advanced the
            // stream's receive offset for them, so the peer will never resend them. Ordered after
            // [pendingTaken] because that chunk came off the front of the queue and is the older of the
            // two — though only one of them can ever be set, since every path that takes from the queue
            // returns immediately.
            delivered?.let { return it }
            throw e
        } catch (_: ClosedSendChannelException) {
            // The connection closed before this read could enqueue its StreamRecv. transitionToClosed
            // drains quiche into the slot *before* closing `commands`, so anything still owed to this
            // stream is queued by the time we can observe the closure — hand it over, don't call End.
            return pendingData() ?: ReadResult.End
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            // Same, for a reader parked on dataSignal when cleanup() closed it.
            return pendingData() ?: ReadResult.End
        } finally {
            // Taken from the queue but never delivered — an external cancellation unwound us after the
            // take (the timeout case returned it above). Put it back so the next read() still gets it,
            // rather than dropping it as the pre-#414 code did.
            //
            // Ordered BEFORE salvageCancelledRecv deliberately: this chunk came off the front of the
            // queue and is therefore older than anything the salvage is about to append. Requeueing
            // after the salvage would reorder the stream.
            //
            // pendingData is Channel.UNLIMITED, so trySend fails only once the channel is closed — and a
            // closed queue means no read() will ever drain it again, so freeing is then the only way not
            // to leak.
            pendingTaken?.let { undelivered ->
                pendingTaken = null
                if (slot.pendingData.trySend(undelivered.buffer).isFailure) {
                    // Unexpected on a healthy stream, and the reason this is worth a trace line: the
                    // queue is closed, so nothing will ever drain this chunk and freeing is the only
                    // alternative to a leak. A STREAM_LOSS/QueueClosed line is the first direct
                    // evidence the #414 window is reachable rather than only real by construction.
                    driver.recorder?.streamLoss(
                        slot.id.id,
                        undelivered.buffer.remaining(),
                        StreamLossCause.QueueClosed,
                    )
                    undelivered.buffer.freeIfNeeded()
                }
            }
            // The driver ALWAYS completes the deferred — in execute() after connStreamRecv, or in
            // cleanup()/failCommand() on teardown (which does NOT dereference `addr`) — so this join can
            // never hang. After it returns, quiche is provably done with `addr`; only then release.
            val abandoned = inFlight
            if (abandoned != null) {
                withContext(NonCancellable) { abandoned.join() }
                // ...and "release" is only right if the command came back empty-handed. We are here
                // because a timeout or a cancel unwound this read, but the driver answered the queued
                // StreamRecv anyway — and quiche has already moved the receive offset for whatever it
                // handed over. Give those bytes (and any FIN with them) to the slot instead of freeing
                // them; see [salvageCancelledRecv] for why dropping them killed streams in the field.
                if (salvageCancelledRecv(abandoned, buffer)) transferred = true
            }
            if (!transferred) buffer.freeNativeMemory()
        }
    }

    override suspend fun streamWrite(
        streamId: QuicStreamId,
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val remaining = buffer.remaining()
        // Empty input: nothing to send (quiche would report 0). Return before touching the buffer's
        // native address — a zero-length buffer may not expose one — and never park on an empty write.
        if (remaining == 0) return 0
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong() + buffer.position()

        // A StreamSend we enqueued but the driver has not yet completed. While this is set, the driver may
        // still READ `addr` inside connStreamSend. The caller owns `buffer` and frees it (or drops its last
        // reference) the instant we return — so on cancellation we must wait for any in-flight send to finish
        // first; otherwise quiche reads freed/Cleaner-reclaimed memory. (A read-after-free is less likely to
        // corrupt the heap than the read path's write-after-free, but it can still fault on an unmapped page,
        // and the lifetime contract must hold symmetrically.)
        var inFlight: CompletableDeferred<StreamSendResult>? = null
        return try {
            withTimeout(timeout) {
                while (true) {
                    val deferred = CompletableDeferred<StreamSendResult>()
                    // trySend, not send — see streamRead: atomic enqueue-or-throw, no
                    // buffered-yet-cancelled state that could skip the finally's join.
                    driver.commands.trySend(QuicheCmd.StreamSend(streamId.id, addr, remaining, false, deferred)).getOrThrow()
                    // Mark in-flight only AFTER a successful enqueue (see streamRead).
                    inFlight = deferred
                    val sent = deferred.await()
                    inFlight = null
                    val written = sent.result
                    when (written) {
                        // Flow-control blocked (QUICHE_ERR_DONE, or a defensive 0 with bytes still pending):
                        // the stream's window is full. Park on writableSignal until the driver observes the
                        // stream become writable again (a MAX_STREAM_DATA / MAX_DATA frame reopened it), then
                        // retry. Reactive — no delay-poll. The CONFLATED signal makes this lost-wakeup-free:
                        // any signal fired after this `await` returned DONE is buffered until we receive it.
                        QuicheDriver.QUICHE_ERR_DONE, 0 -> slot.writableSignal.receive()
                        // Progress: quiche accepted ≥1 byte. Return the (possibly partial) count — the buffer
                        // is untouched (zero-copy); the caller advances by it and re-enters for the remainder.
                        else ->
                            if (written > 0) {
                                return@withTimeout written
                            } else if (written == QuicheDriver.QUICHE_ERR_STREAM_STOPPED ||
                                written == QuicheDriver.QUICHE_ERR_STREAM_RESET
                            ) {
                                // Peer sent STOP_SENDING / RESET_STREAM on THIS stream (RFC 9000 §19.4-19.5).
                                // Stream-scoped, not connection loss — surface a stream error the caller can
                                // catch to abandon just this stream; the connection keeps every other stream.
                                // quiche reports the direction via the sentinel and the peer application
                                // error code via out_error_code — surfaced by ALL THREE bindings (FFM, JNI,
                                // cinterop) on STREAM_STOPPED / STREAM_RESET (0 if the peer used 0), so it is
                                // always present here.
                                val code =
                                    requireNotNull(sent.errorCode) {
                                        "quiche STREAM_STOPPED/RESET must carry out_error_code"
                                    }
                                val abort =
                                    if (written == QuicheDriver.QUICHE_ERR_STREAM_STOPPED) {
                                        QuicStreamAbort.StopSending(code)
                                    } else {
                                        QuicStreamAbort.ResetStream(code)
                                    }
                                throw QuicStreamException(
                                    streamId.id,
                                    abort,
                                    "quiche stream ${streamId.id} aborted by peer (error $written)",
                                )
                            } else {
                                throw QuicCloseException(
                                    driver.closeReasonOr(QuicError.InternalError("quiche stream write error: $written")),
                                    "quiche stream write error: $written",
                                    attribution = driver.closeAttribution(),
                                )
                            }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                0
            }
        } catch (_: ClosedSendChannelException) {
            throw driver.connectionClosed()
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            // writableSignal was closed by cleanup() — the connection went away while we were parked.
            throw driver.connectionClosed()
        } finally {
            // Wait — non-cancellably — for any in-flight StreamSend to finish reading `addr` before we
            // return to the caller who will free `buffer`. The driver always completes the deferred
            // (execute() after connStreamSend, or failCommand() on teardown), so this never hangs.
            inFlight?.let { withContext(NonCancellable) { it.join() } }
        }
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        try {
            val deferred = CompletableDeferred<StreamSendResult>()
            driver.commands.send(QuicheCmd.StreamSend(streamId.id, 0L, 0, true, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Connection already closed
        }
    }

    override suspend fun streamShutdown(
        streamId: QuicStreamId,
        direction: Int,
        errorCode: Long,
    ) {
        try {
            val deferred = CompletableDeferred<Int>()
            driver.commands.send(QuicheCmd.StreamShutdown(streamId.id, direction, errorCode, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Connection already closed — nothing to shut down.
        }
    }
}

/**
 * quiche yielded more retired connection IDs than the count it reported a moment earlier, so the
 * excess was lost — and a lost id keeps routing to a connection that no longer recognises it, which
 * is #437 returning silently.
 *
 * Unreachable by construction: [QuicheDriver.drainRetiredScids] reads the count and drains on the same
 * driver coroutine, the only one allowed to touch the connection. It exists so the impossible case is
 * *recorded* rather than assumed — as a type, because the trace channel takes qualified class names
 * and never bare strings.
 */
internal class RetiredScidOverflow(
    yielded: Int,
    capacity: Int,
) : IllegalStateException(
        "quiche yielded $yielded retired connection ids into room for $capacity; " +
            "${yielded - capacity} will keep routing to a connection that no longer knows them",
    )
