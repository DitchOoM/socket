@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration

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
    private val api: QuicheApi,
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
     * The driver's clock seam (monotonic mark + the `select` timeout clause). Defaults to
     * [RealDriverClock] so every platform and production path keeps its exact pre-seam timer
     * behaviour; tests inject a manual clock to make the keepalive/idle timing deterministic.
     */
    private val clock: DriverClock = RealDriverClock,
    /**
     * Connection-migration wiring (slice 3). All default to "disabled" so server-accepted
     * drivers, unit-test fakes, and the no-migration platforms keep their single-path
     * behaviour untouched. A client setup that supports migration passes the peer sockaddr,
     * the primary local sockaddr, and a [UdpChannelFactory] for opening additional paths.
     */
    private val peerAddr: Long = 0L,
    private val peerLen: Int = 0,
    private val primaryLocalAddr: Long = 0L,
    private val primaryLocalLen: Int = 0,
    private val udpChannelFactory: UdpChannelFactory? = null,
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
     * Server-only: invoked with each spare source CID issued by [issueSpareCids], before that
     * scid buffer is freed. The server registers the CID in its DCID->driver routing map, because
     * a migrating peer switches to a *new* DCID (one of these issued CIDs) on the new path; without
     * the mapping those packets miss the demux, look like a new connection, and get dropped — the
     * server never sees the PATH_CHALLENGE and validation fails. Clients leave it null (they demux
     * incoming packets by their per-path socket, not by an app-level DCID map).
     */
    private val onScidIssued: ((PlatformBuffer, Int) -> Unit)? = null,
) {
    val commands = Channel<QuicheCmd>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    val state: StateFlow<QuicConnectionState> = _state

    /**
     * The structured QUIC reason to report when an operation fails because the connection is gone:
     * the recorded close error if the connection has reached [QuicConnectionState.Closed], otherwise
     * [fallback]. Connection state is the single source of truth for the close reason — the driver,
     * [DriverStreamAdapter], and every platform facade funnel through here so a [QuicCloseException]
     * always carries the most specific reason available.
     */
    fun closeReasonOr(fallback: QuicError): QuicError = (state.value as? QuicConnectionState.Closed)?.error ?: fallback

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
     * Client-mode recv buffer pool — mirrors the server-side pool in
     * CommonJvmWithQuicServer. Only allocated in [clientMode] because
     * server-accepted drivers receive packets via commands.send() from the
     * server's receive loop and never run [udpReaderLoop].
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
    private val recvBufPool: BufferPool? =
        if (clientMode) {
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = 64,
                defaultBufferSize = MAX_DATAGRAM_SIZE,
                factory = bufferFactory,
            )
        } else {
            null
        }

    /**
     * One network path the connection can send/receive on. The connection always has
     * a [primary] path; active migration ([handleMigrate]) opens more. Routing stays
     * dormant while there is a single path — [flushOutgoing] sends straight to
     * [primary] and never decodes — so single-path behaviour is byte-for-byte unchanged.
     */
    private inner class PathEntry(
        val key: PathKey,
        val channel: UdpChannel,
        val recvInfo: QuicheRecvInfo,
        val localAddr: Long,
        val localLen: Int,
        val isPrimary: Boolean,
        val release: () -> Unit,
    ) {
        var readerJob: Job? = null
    }

    private val primary =
        PathEntry(
            key = if (primaryLocalAddr != 0L) api.decodePathKey(primaryLocalAddr) else PathKey(0, 0, 0L, 0L),
            channel = udpChannel,
            recvInfo = recvInfo,
            localAddr = primaryLocalAddr,
            localLen = primaryLocalLen,
            isPrimary = true,
            // Primary sockaddr lifetime is owned by the connection setup's onCleanup; nothing to release here.
            release = {},
        )
    private val paths = mutableMapOf(primary.key to primary)

    /** True only for a client connection wired with a [UdpChannelFactory] and peer/local sockaddrs. */
    private val migrationEnabled: Boolean = clientMode && udpChannelFactory != null && peerAddr != 0L && primaryLocalAddr != 0L

    private var activeKey: PathKey = primary.key
    private var pendingMigration: PendingMigration? = null
    private var spareCidsIssued = false

    private val _pathState = MutableStateFlow(PathInfo())
    val pathState: StateFlow<PathInfo> = _pathState

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

    private inner class PendingMigration(
        val key: PathKey,
        val localHost: String?,
        val localPort: Int,
        val result: CompletableDeferred<MigrationResult>,
    )

    fun start(scope: CoroutineScope) {
        driverScope = scope
        driverJob = scope.launch(Dispatchers.Default) { run() }

        if (clientMode) {
            startReaderLoop(primary)
        }
    }

    private fun startReaderLoop(entry: PathEntry) {
        val scope = driverScope ?: return
        entry.readerJob = scope.launch(Dispatchers.Default) { udpReaderLoop(entry) }
    }

    /**
     * The reactive driver loop. Suspends on command channel or quiche timeout — zero CPU when idle.
     * Timeout is integrated via [select]: no separate timeout coroutine, no polling.
     */
    private suspend fun run() {
        try {
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
                val wait =
                    when {
                        connTimeout != null && keepAliveRemaining != null -> minOf(connTimeout, keepAliveRemaining)
                        else -> connTimeout ?: keepAliveRemaining
                    }
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
                        handleMigrate(cmd) // suspends: opens a socket
                        lastActivity = clock.markNow()
                    }
                    cmd != null -> {
                        execute(cmd)
                        lastActivity = clock.markNow() // any command is activity → defer keepalive
                    }
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
                afterCommand()
            }
        } finally {
            cleanup()
        }
    }

    private fun execute(cmd: QuicheCmd) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> {
                val addr =
                    cmd.buf.nativeMemoryAccess!!
                        .nativeAddress
                        .toLong()
                // Hand quiche the recv_info for this packet. Server: a per-datagram override whose
                // `from` is the real source (passive migration). Client: the path the packet arrived
                // on (local addr = that socket's). Null/unknown falls back to primary — single-path
                // is unchanged.
                val info = cmd.recvInfoOverride ?: cmd.pathKey?.let { paths[it]?.recvInfo } ?: recvInfo
                api.connRecv(conn, addr, cmd.len, info)
                cmd.buf.freeNativeMemory()
                // Signal the server it may now release the cached recv_info (quiche copied
                // what it needs during connRecv; the pointer is no longer referenced).
                cmd.onRecvInfoConsumed?.invoke()
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
                cmd.result.complete(slot)
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

            is QuicheCmd.Close -> {
                api.connClose(conn, cmd.error)
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

    /** Unreachable in practice — [run] routes [QuicheCmd.Migrate] to the suspending [handleMigrate]. */
    private fun handleMigrateSync(cmd: QuicheCmd.Migrate) {
        cmd.result.complete(MigrationResult.Failed("migrate dispatched on non-suspending path"))
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
                    val byteStream = QuicheStreamByteStream(streamId, adapter, bufferFactory)
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
            _state.value = QuicConnectionState.Established("h3")
            issueSpareCids()
        }
        if (api.connIsClosed(conn)) {
            transitionToClosed()
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
     */
    private fun transitionToClosed() {
        if (_state.value is QuicConnectionState.Closed) return
        commands.close()
        _state.value = QuicConnectionState.Closed(resolveCloseError())
    }

    /**
     * The typed [QuicError] for why the connection closed, or `null` for a clean shutdown. Prefers the
     * **peer's** CONNECTION_CLOSE (the remote tore us down — e.g. a strict server rejecting our streams
     * or transport params) over our **local** close (quiche itself aborted — handshake/TLS failure,
     * protocol violation), since the peer's reason is the more actionable one when both exist. quiche is
     * single-threaded; this runs on the driver loop alongside [updateState], so the reads are safe.
     * Both helpers default to `null` on backends that don't bind the C calls yet (JNI/Android), so the
     * behavior there is unchanged (clean-looking close).
     */
    private fun resolveCloseError(): QuicError? {
        (api.connPeerError(conn) ?: api.connLocalError(conn))
            ?.takeUnless { it is QuicError.NoError }
            ?.let { return it }
        // No CONNECTION_CLOSE frame: distinguish an idle/handshake-stall timeout (a local event, no wire
        // code) from a genuinely clean shutdown — otherwise a stalled connection looks like NoError.
        if (api.connIsTimedOut(conn)) return QuicError.IdleTimeout
        return null
    }

    private suspend fun flushOutgoing() {
        while (true) {
            val written = api.connSend(conn, sendAddr, MAX_DATAGRAM_SIZE, sendInfo)
            if (written <= 0) break
            // Route by the local egress address quiche chose. With a single path this is
            // dormant — send straight to primary, no decode — so the common case is unchanged.
            val channel =
                if (paths.size <= 1) {
                    primary.channel
                } else {
                    paths[api.decodePathKey(api.sendInfoFromAddr(sendInfo))]?.channel ?: primary.channel
                }
            // Server egress follows the peer: send to the destination quiche chose (sendInfo.to) so
            // a migrated client's new source receives replies. Clients leave this null and rely on
            // their connected/path sockets. NioUdpChannel caches the reconstruction (steady state
            // targets one address), so the non-migrating server path stays allocation-free.
            val dest = if (isServer) api.decodePathKey(api.sendInfoToAddr(sendInfo)) else null
            try {
                channel.send(udpSendBuf, written, dest)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                // UDP send failed (peer unreachable, channel closed during shutdown, etc).
                // The connection cannot make further progress — short-circuit to Closed and
                // let the driver loop unwind via cleanup(). Letting the exception escape
                // would leak it as an uncaught coroutine failure into the parent scope.
                transitionToClosed()
                return
            }
        }
    }

    /**
     * Client-mode: async UDP reader for one [entry]'s socket. Suspends until data
     * arrives — zero CPU when no packets. Tags each packet with [PathEntry.key] so
     * the driver feeds quiche the right recv_info during migration.
     */
    private suspend fun udpReaderLoop(entry: PathEntry) {
        val pool = recvBufPool!!
        try {
            while (coroutineContext[Job]?.isActive != false) {
                val buf = pool.allocate(MAX_DATAGRAM_SIZE)
                val received =
                    try {
                        entry.channel.receive(buf)
                    } catch (_: Exception) {
                        buf.freeNativeMemory()
                        if (commands.isClosedForSend) return
                        continue
                    }
                if (received > 0) {
                    commands.send(QuicheCmd.RecvPacket(buf, received, entry.key))
                } else {
                    buf.freeNativeMemory()
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
        val factory = udpChannelFactory
        if (!migrationEnabled || factory == null) {
            cmd.result.complete(MigrationResult.Unsupported)
            return
        }
        if (pendingMigration != null) {
            cmd.result.complete(MigrationResult.Failed("a migration is already in progress"))
            return
        }
        if (api.connAvailableDcids(conn) <= 0L) {
            cmd.result.complete(MigrationResult.Failed("no spare destination connection id available"))
            return
        }

        val newPath =
            try {
                factory.openPath(cmd.localHost, cmd.localPort)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                cmd.result.complete(MigrationResult.Failed("openPath failed: ${e.message}"))
                return
            }

        val key = api.decodePathKey(newPath.localSockAddrAddress)
        val pathRecvInfo = api.recvInfoNew(peerAddr, peerLen, newPath.localSockAddrAddress, newPath.localSockAddrLength)
        val entry =
            PathEntry(
                key = key,
                channel = newPath.channel,
                recvInfo = pathRecvInfo,
                localAddr = newPath.localSockAddrAddress,
                localLen = newPath.localSockAddrLength,
                isPrimary = false,
                release = newPath.release,
            )
        paths[key] = entry

        val rc = api.connProbePath(conn, entry.localAddr, entry.localLen, peerAddr, peerLen, addr(seqScratch))
        if (rc < 0) {
            teardownPath(entry)
            cmd.result.complete(MigrationResult.Failed("probe_path failed: rc=$rc"))
            return
        }

        pendingMigration = PendingMigration(key, cmd.localHost, cmd.localPort, cmd.result)
        _pathState.value = PathInfo(MigrationPhase.Probing, cmd.localHost, cmd.localPort)
        startReaderLoop(entry) // PATH_CHALLENGE egresses the new socket via flushOutgoing routing
    }

    /** Poll and react to quiche path events (validation, failure, path close). Migration-clients only. */
    private fun drainPathEvents() {
        while (true) {
            val type = api.connPathEventNext(conn, addr(peLocalOut), addr(peLocalLenOut), addr(pePeerOut), addr(pePeerLenOut)) ?: break
            when (type) {
                QuichePathEventType.Validated -> {
                    val key = api.decodePathKey(addr(peLocalOut))
                    val pending = pendingMigration ?: continue
                    if (pending.key != key) continue
                    val entry = paths[key]
                    if (entry == null) {
                        completeMigration(pending, MigrationResult.Failed("validated path missing"), MigrationPhase.Failed)
                        continue
                    }
                    _pathState.value = PathInfo(MigrationPhase.Validated, pending.localHost, pending.localPort)
                    val rc = api.connMigrate(conn, entry.localAddr, entry.localLen, peerAddr, peerLen, addr(seqScratch))
                    if (rc >= 0) {
                        activeKey = key
                        completeMigration(pending, MigrationResult.Succeeded(pending.localHost, pending.localPort), MigrationPhase.Migrated)
                    } else {
                        completeMigration(pending, MigrationResult.Failed("migrate failed: rc=$rc"), MigrationPhase.Failed)
                    }
                }

                QuichePathEventType.FailedValidation -> {
                    val key = api.decodePathKey(addr(peLocalOut))
                    val pending = pendingMigration
                    if (pending != null && pending.key == key) {
                        paths[key]?.let { teardownPath(it) }
                        completeMigration(pending, MigrationResult.Failed("path validation failed"), MigrationPhase.Failed)
                    }
                }

                QuichePathEventType.Closed -> {
                    val key = api.decodePathKey(addr(peLocalOut))
                    paths[key]?.let { if (!it.isPrimary) teardownPath(it) }
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

    private fun completeMigration(
        pending: PendingMigration,
        result: MigrationResult,
        phase: MigrationPhase,
    ) {
        _pathState.value = PathInfo(phase, pending.localHost, pending.localPort)
        pending.result.complete(result)
        pendingMigration = null
    }

    /** Cancel a non-primary path's reader, close its socket, and free its recv_info + sockaddr. */
    private fun teardownPath(entry: PathEntry) {
        if (entry.isPrimary) return
        paths.remove(entry.key)
        entry.readerJob?.cancel()
        try {
            entry.channel.close()
        } catch (_: Exception) {
        }
        api.recvInfoFree(entry.recvInfo) // free recv_info before the sockaddr it references
        entry.release()
    }

    /**
     * Supply spare source connection IDs to the peer once established. quiche does not
     * auto-issue CIDs, so without this the peer has no spare destination CID to migrate
     * to ([connAvailableDcids] stays 0). Issued once per connection (both ends), bounded
     * by [connScidsLeft] (which reflects the peer's active_connection_id_limit).
     */
    private fun issueSpareCids() {
        if (spareCidsIssued) return
        spareCidsIssued = true
        var count = 0
        while (count < MAX_SPARE_SCIDS && api.connScidsLeft(conn) > 0L) {
            val scid = generateScid(bufferFactory) // 20 random bytes, reset for read
            val token = bufferFactory.allocate(STATELESS_RESET_TOKEN_LEN)
            repeat(STATELESS_RESET_TOKEN_LEN) { token.writeByte(Random.nextInt(256).toByte()) }
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
            // Surface the issued CID (server registers it for routing) before freeing the buffer.
            if (rc >= 0) onScidIssued?.invoke(scid, QUIC_MAX_CONN_ID_LEN)
            scid.freeNativeMemory()
            token.freeNativeMemory()
            if (rc < 0) break
            count++
        }
    }

    private fun cleanup() {
        commands.close()

        while (true) {
            val cmd = commands.tryReceive().getOrNull() ?: break
            failCommand(cmd)
        }

        // A migration still in flight when the connection dies never completes — fail it.
        pendingMigration?.result?.complete(MigrationResult.Failed("connection closed"))
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
        // with the driver.
        recvBufPool?.clear()
        incomingStreams.close()
        // Released last — quiche may have dereferenced recvInfo.from/to inside
        // any of the api.*Free() calls above. Safe to release the underlying
        // sockaddr storage only after the conn/recvInfo handles are gone.
        onCleanup()
    }

    private fun failCommand(cmd: QuicheCmd) {
        when (cmd) {
            is QuicheCmd.RecvPacket -> {
                cmd.buf.freeNativeMemory()
                // Dropped without connRecv — still release the server's in-flight ref.
                cmd.onRecvInfoConsumed?.invoke()
            }
            is QuicheCmd.OpenStream ->
                cmd.result.completeExceptionally(
                    QuicCloseException(closeReasonOr(QuicError.NoError), "connection closed"),
                )
            is QuicheCmd.StreamRecv -> cmd.result.complete(StreamRecvResult.Error(-2))
            is QuicheCmd.StreamSend -> cmd.result.complete(StreamSendResult(-1))
            // The stream/connection is gone; the shutdown frame won't go out, which is fine — the
            // peer already sees the connection closing. Complete with a benign 0 (no-op).
            is QuicheCmd.StreamShutdown -> cmd.result.complete(0)
            // Datagrams: receive → Error maps to ConnectionClosed; send → -1 parks on the (now-closed)
            // dgramWritableSignal, which throws QuicCloseException. Mirrors the stream cases above.
            is QuicheCmd.DgramRecv -> cmd.result.complete(StreamRecvResult.Error(-2))
            is QuicheCmd.DgramSend -> cmd.result.complete(-1)
            // Connection gone before the cert could be read — report "no certificate" (0); the verifier
            // turns that into a handshake failure, which is the right outcome for a torn-down connection.
            is QuicheCmd.PeerCert -> cmd.result.complete(0)
            is QuicheCmd.Close -> cmd.result.complete(Unit)
            is QuicheCmd.Migrate -> cmd.result.complete(MigrationResult.Failed("connection closed"))
        }
    }

    suspend fun destroy() {
        commands.close()
        driverJob?.join()
    }

    companion object {
        const val MAX_DATAGRAM_SIZE = 1350

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
    override suspend fun streamRead(
        streamId: QuicStreamId,
        bufferFactory: BufferFactory,
        bufferSize: Int,
        timeout: Duration,
    ): ReadResult {
        val buffer = bufferFactory.allocate(bufferSize)
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong()

        // A StreamRecv we enqueued but the driver has not yet completed. While this is set, the driver may
        // still be about to WRITE received bytes into `addr` inside connStreamRecv. The command channel is
        // UNLIMITED so `commands.send` never suspends — by the time a timeout or external cancellation can
        // unwind us, the command is already queued. If we let `buffer` be released here (freed below, or for
        // a heap/GC-backed buffer simply dropped so its Cleaner reclaims the native memory) before the
        // driver finishes, quiche writes into freed memory and corrupts the native heap (the rare
        // "SIGSEGV in malloc" crash). So on every exit we first wait — non-cancellably — for any in-flight
        // StreamRecv to complete, then release the buffer.
        var inFlight: CompletableDeferred<StreamRecvResult>? = null
        var transferred = false
        try {
            return withTimeout(timeout) {
                // The FIN may have arrived coalesced with the last data chunk on a previous read()
                // (which returned that Data and recorded the FIN here). quiche has already delivered it,
                // so there is no further data and no readable-signal coming — return End now instead of
                // issuing a StreamRecv that returns Done and parking on dataSignal forever.
                if (slot.finReceived) {
                    return@withTimeout ReadResult.End
                }
                while (true) {
                    val deferred = CompletableDeferred<StreamRecvResult>()
                    driver.commands.send(QuicheCmd.StreamRecv(streamId.id, addr, bufferSize, deferred))
                    // Mark in-flight only AFTER a successful enqueue: if send threw (channel closed) the
                    // command never reached the driver, so there is nothing to join (joining it would hang).
                    inFlight = deferred
                    val result = deferred.await()
                    inFlight = null
                    when (result) {
                        is StreamRecvResult.Data -> {
                            // Record the FIN whether or not this chunk also carried data — a coalesced
                            // FIN (bytes > 0 && fin) is otherwise dropped, wedging the next read().
                            if (result.fin) slot.finReceived = true
                            if (result.bytesRead > 0) {
                                buffer.position(result.bytesRead)
                                buffer.resetForRead()
                                // Ownership transfers to the caller — do not release in the finally.
                                transferred = true
                                return@withTimeout ReadResult.Data(buffer)
                            }
                            // bytesRead == 0 implies a pure FIN (fin == true) — clean end of stream.
                            return@withTimeout ReadResult.End
                        }
                        is StreamRecvResult.Done -> {
                            // Defensive: if the FIN was consumed earlier (coalesced with data), no signal
                            // is coming — end now rather than park forever.
                            if (slot.finReceived) {
                                return@withTimeout ReadResult.End
                            }
                            slot.dataSignal.receive()
                            continue
                        }
                        is StreamRecvResult.Error -> {
                            return@withTimeout ReadResult.End
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                ReadResult.End
            }
        } catch (_: ClosedSendChannelException) {
            return ReadResult.End
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            return ReadResult.End
        } finally {
            // The driver ALWAYS completes the deferred — in execute() after connStreamRecv, or in
            // cleanup()/failCommand() on teardown (which does NOT dereference `addr`) — so this join can
            // never hang. After it returns, quiche is provably done with `addr`; only then release.
            inFlight?.let { withContext(NonCancellable) { it.join() } }
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
                    driver.commands.send(QuicheCmd.StreamSend(streamId.id, addr, remaining, false, deferred))
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
                                )
                            }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                0
            }
        } catch (_: ClosedSendChannelException) {
            throw QuicCloseException(driver.closeReasonOr(QuicError.NoError), "connection closed")
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            // writableSignal was closed by cleanup() — the connection went away while we were parked.
            throw QuicCloseException(driver.closeReasonOr(QuicError.NoError), "connection closed")
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
