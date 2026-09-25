@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.SocketAddressCodec
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Per-**path** impairment — the piece [ImpairmentConfig] structurally cannot express.
 *
 * `ImpairedPipe` takes `latency`/`jitter` for the whole pipe, which is fine for a connection that
 * lives on one path forever and useless for migration: the interesting scenarios are all *asymmetries
 * between* paths. "The old path is 80ms and the new one is 35ms" is the #445 overtake window stated as
 * a config value — the new path's packets reach the server ahead of the old path's still-in-flight
 * ones, which is the only condition under which a retired CID can arrive late. Measured 2026-08-22: a
 * loopback burst of 12 migrations survives both patched and unpatched quiche (RTT≈0 leaves no window),
 * while a real ~40ms path reproduced it immediately.
 *
 * [reach] is the #447 condition: a [PathReach.Dark] path swallows everything, so the PATH_CHALLENGE
 * is never answered and validation runs out its RFC 9000 §8.2.4 budget. It is a property of the path
 * rather than of the pipe so one path can die while the others stay healthy — which is what a real
 * handoff looks like and what `ImpairedPipe.blackhole` (whole-pipe) cannot model.
 * [PathReach.DarkUntil] is the walk's cellular attach: a link that is dark for a while and then
 * answers every path on it, which no per-path or per-index blackhole can express. [PathReach.HeldUntil]
 * is the same attach on a link that queues instead of dropping. [LinkReach] gives each direction its
 * own reach, because a link can fail one way only.
 *
 * [mtu] is #637's condition, and it is keyed on *size* where [reach] is keyed on time: a link that
 * carries the connection perfectly and silently swallows anything past [LinkMtu.Bounded.bytes]. A
 * tunnel, a PPPoE hop or a v6 leg at the 1280-byte minimum all look like this, and none of them can be
 * expressed by loss (which is random, so a retry eventually gets through) or by [PathReach.Dark]
 * (which kills the path outright, so nothing proves the small datagrams still flow).
 *
 * [sendReturn] is the client platform's side of a held uplink: whether `send` answers at once or only
 * once the link takes the datagram. [PathReach] delays the datagram on the wire; this delays the call.
 */
internal data class PathImpairment(
    val latency: Duration = Duration.ZERO,
    val jitter: Duration = Duration.ZERO,
    val loss: Double = 0.0,
    val reach: LinkReach = LinkReach.Open,
    val mtu: LinkMtu = LinkMtu.Unbounded,
    val sendReturn: SendReturn = SendReturn.Immediately,
)

/** Which way a datagram crosses a path, from the client's side: [Uplink] is client→server. */
internal enum class LinkDirection { Uplink, Downlink }

/**
 * A path's [PathReach] in each direction.
 *
 * The 2026-09-20 iPhone walk (leg 3, connection 1) is why this is two values: every PATH_CHALLENGE
 * the phone sent reached the server, which answered each within 0.1 ms, and not one datagram
 * of any size came back for 30 s. A reach shared by both directions can only say "the path is dark",
 * which also stops the probes, so the server never answers and nothing distinguishes the two
 * failures. Each direction takes any [PathReach], so a link that is dark one way and attaches late
 * the other is a value like any other.
 */
internal data class LinkReach(
    val uplink: PathReach,
    val downlink: PathReach,
) {
    /** The same reach both ways: a link that dies, attaches or holds as a whole. */
    constructor(bothWays: PathReach) : this(bothWays, bothWays)

    fun toward(direction: LinkDirection): PathReach =
        when (direction) {
            LinkDirection.Uplink -> uplink
            LinkDirection.Downlink -> downlink
        }

    companion object {
        val Open: LinkReach = LinkReach(PathReach.Open)
    }
}

/**
 * When the client endpoint's `send` returns to the driver.
 *
 * The 2026-09-25 iPhone walk is why this exists: the cellular uplink held packets for 12–47 s while the
 * downlink kept delivering, and Network.framework withheld each send's completion for as long as the
 * uplink held it. Every real backend answered at once before that, so the sim's endpoint did too, and
 * the driver's send-stall bound had never been reached with the downlink still alive.
 */
internal sealed interface SendReturn {
    /** The platform takes the datagram and answers at once. */
    data object Immediately : SendReturn

    /**
     * The platform queues the datagram and answers only at [at] (virtual time from the run's t0), when
     * the uplink releases it. The datagram is on the platform's queue from the call on, so a caller
     * that stops waiting does not take it back: it leaves at [at] either way.
     */
    data class WithheldUntil(
        val at: Duration,
    ) : SendReturn
}

/**
 * Whether a path carries datagrams, and when: always, never, only once the sim's clock reaches an
 * instant, or late — everything offered before an instant held and released, in order, at it.
 */
internal sealed interface PathReach {
    data object Open : PathReach

    data object Dark : PathReach

    /** Dark before [at] (virtual time from the run's t0), open from [at] on. */
    data class DarkUntil(
        val at: Duration,
    ) : PathReach

    /**
     * Everything offered before [at] is held and delivered, in the order it was offered, [at] plus the
     * path's latency; open from [at] on. A link that queues through an attach rather than dropping.
     */
    data class HeldUntil(
        val at: Duration,
    ) : PathReach
}

/** Whether the client has closed a path's socket. The link outlives it: the peer can still send to it. */
internal sealed interface SimSocket {
    data object Open : SimSocket

    data object Closed : SimSocket
}

/**
 * The largest datagram a link carries: any size, or nothing past [Bounded.bytes].
 *
 * The sibling of [PathReach], one axis over. [PathReach] answers "does this link carry datagrams *at
 * this moment*"; this answers "does it carry *this datagram*". Separate values rather than one
 * combined state because they compose — the walk's cellular leg attaches late **and** is an MTU-1280
 * v6 link — and because a drop for size consumes no RNG draws for the same reason a dark drop does
 * not: bounding a link's MTU must not shift the seeded sequence of everything around it.
 */
internal sealed interface LinkMtu {
    data object Unbounded : LinkMtu

    /** Datagrams of more than [bytes] are swallowed; everything at or below it is carried normally. */
    data class Bounded(
        val bytes: Int,
    ) : LinkMtu
}

/**
 * A multi-path in-memory UDP substrate: one server endpoint and N client-side local endpoints, each
 * with its own [PathImpairment], joined with no OS sockets anywhere.
 *
 * ## Why this is not an option on [ImpairedPipe]
 * [ImpairedPipe] is a *pair* of endpoints. Migration needs three things it has no place to put: more
 * than one client-side local address, per-address impairment, and — the one that actually forces a new
 * type — a **source-carrying receive on the server side**. quiche only recognises a client's new path
 * if the server hands it a `recv_info` whose `from` is the datagram's real origin, and
 * [UdpChannel.receive] returns `Int`. So the server here does not consume a [UdpChannel] at all: it
 * pumps [receiveAtServer], which yields the bytes *and* the source. That is exactly what
 * `SharedQuicheServer` does with its per-source `recv_info` cache, so the sim's server side is modelled
 * on the production server rather than on a second client.
 *
 * ## Determinism
 * One seeded [Random] under a lock draws a **fixed two draws per datagram** (loss roll, jitter
 * fraction) in pipe-arrival order, the same discipline [ImpairmentConfig] documents: the decision
 * sequence is a pure function of the seed and arrival order, never of which impairments are enabled.
 * A dark-path ([PathReach]) drop consumes **no** draws in either direction, so killing a path, or
 * one direction of it, mid-run does not shift the seeded sequence of everything around it.
 *
 * Delivery uses `delay()` on [scope], so under `runTest` the whole substrate runs on virtual time and
 * an 80ms path costs no wall clock. Zero computed delay enqueues synchronously, keeping a
 * zero-latency path strictly FIFO.
 */
internal class MultiPathPipe(
    seed: Long,
    private val scope: CoroutineScope,
    private val api: QuicheApi,
    private val bufferFactory: BufferFactory,
    private val codec: SocketAddressCodec,
    private val ledger: DatagramLedger,
    /** The sim's clock, for [PathReach.DarkUntil] — virtual under `runTest`, monotonic on a wall clock. */
    private val now: () -> Duration,
) {
    private val rng = Random(seed)
    private val lock = SynchronizedObject()

    /** One datagram as the server sees it: the payload plus the client local address that sent them. */
    internal class ServerDatagram(
        val datagram: PipeDatagram,
        val from: SocketAddress,
    )

    // onUndeliveredElement is the seam for a datagram the channel accepted but no receiver ever got —
    // the server pump cancelled while suspended in receive() is the case that actually bites, and it
    // leaks exactly one datagram per run without this.
    private val toServer = Channel<ServerDatagram>(Channel.UNLIMITED) { ledger.release(it.datagram) }

    /** Per-path counters, so a test can assert *which* path carried what rather than a pipe total. */
    internal class PathStats {
        @Volatile var sentToServer = 0

        @Volatile var sentToClient = 0

        @Volatile var dropped = 0

        @Volatile var blackholed = 0

        /** Datagrams this path actually handed to the server — [sentToServer] less everything the link swallowed. */
        @Volatile var deliveredToServer = 0

        /** Datagrams this path actually handed to the client — [sentToClient] less everything the link swallowed. */
        @Volatile var deliveredToClient = 0

        /** Datagrams the link swallowed for exceeding its [LinkMtu.Bounded.bytes] — #637's observable. */
        @Volatile var oversized = 0

        /** Datagrams that reached this path's client socket after the client had closed it. */
        @Volatile var arrivedAtClosedSocket = 0

        /** Withheld sends ([SendReturn.WithheldUntil]) whose caller stopped waiting before the platform answered. */
        @Volatile var abandonedSends = 0

        /** Sends the client attempted on this path's socket after closing it — each one refused. */
        @Volatile var sentOnClosedSocket = 0

        /**
         * The largest datagram either endpoint has handed this link, carried or not — how big the
         * *sender* decided to make one, which is the other half of #637: a fix that expanded everything
         * to the RFC floor would validate every constrained link and quietly cost 11% of every payload.
         */
        @Volatile var largestOffered = 0
    }

    internal inner class Path(
        val local: SocketAddress,
        val sockAddr: EncodedSockAddr,
        val key: PathKey,
        impairment: PathImpairment,
    ) {
        @Volatile var impairment: PathImpairment = impairment

        val stats = PathStats()
        val inbound = Channel<PipeDatagram>(Channel.UNLIMITED) { ledger.release(it) }

        /** Whether the client has closed this path's socket; a closed socket takes and sends nothing. */
        @Volatile var socket: SimSocket = SimSocket.Open

        val channel: UdpChannel = ClientEndpoint(this)
    }

    private val pathsByKey = LinkedHashMap<PathKey, Path>()
    private val pathsByAddr = LinkedHashMap<SocketAddress, Path>()

    /** Every path opened so far, in open order — path 0 is the primary. */
    fun paths(): List<Path> = synchronized(lock) { pathsByAddr.values.toList() }

    fun pathAt(local: SocketAddress): Path = synchronized(lock) { requireNotNull(pathsByAddr[local]) { "no path at $local" } }

    /**
     * Register a client-side local endpoint. The pinned sockaddr is what the driver decodes into the
     * [PathKey] it routes egress by, and what the server's `recv_info.from` must reproduce for quiche to
     * see this as a distinct path — so both sides are derived from one encoding here, and they cannot
     * disagree.
     */
    fun openPath(
        local: SocketAddress,
        impairment: PathImpairment = PathImpairment(),
    ): Path {
        val sockAddr = codec.encodeToNative(local, bufferFactory)
        val key = api.decodePathKey(sockAddr.address)
        val path = Path(local, sockAddr, key, impairment)
        synchronized(lock) {
            check(pathsByAddr[local] == null) { "a path is already open at $local" }
            pathsByKey[key] = path
            pathsByAddr[local] = path
        }
        return path
    }

    /** Change a live path's impairment — how a test kills or heals a path mid-connection. */
    fun impair(
        local: SocketAddress,
        impairment: PathImpairment,
    ) {
        pathAt(local).impairment = impairment
    }

    /**
     * The server's receive: bytes plus the real source address. The sim's server pump turns each of
     * these into a `recv_info(from = source)` exactly as `SharedQuicheServer` does, which is what lets
     * quiche recognise a probe arriving from a new client address as a new path.
     */
    suspend fun receiveAtServer(): ServerDatagram =
        try {
            toServer.receive()
        } catch (_: ClosedReceiveChannelException) {
            awaitCancellation()
        }

    /**
     * The server's egress. quiche fills `send_info.to` with the client address it is replying to and
     * [QuicheDriver.flushOutgoing] passes that through as [SendTarget.ServerReply.to], so routing a reply
     * back to the right client path is a map lookup — the sim's stand-in for a real UDP socket's
     * destination address. A destination naming no known path is dropped rather than broadcast: that is a
     * server replying to somewhere the sim never opened, and silently delivering it anyway would hide
     * exactly the routing bug this harness exists to find.
     */
    val serverEgress: UdpChannel = ServerEndpoint()

    fun close() {
        toServer.close()
        // Whatever is still queued was captured and will never be delivered; free it here or it is a
        // leak the ledger will (correctly) report against this pipe.
        while (true) {
            val queued = toServer.tryReceive().getOrNull() ?: break
            ledger.release(queued.datagram)
        }
        synchronized(lock) {
            pathsByAddr.values.forEach {
                it.inbound.close()
                while (true) {
                    val queued = it.inbound.tryReceive().getOrNull() ?: break
                    ledger.release(queued)
                }
                it.sockAddr.free()
            }
            pathsByAddr.clear()
            pathsByKey.clear()
        }
    }

    /**
     * Apply [path]'s impairment to one datagram crossing it toward [direction], and schedule (or drop)
     * it. Latency, jitter, loss and MTU belong to the link, so an 80ms path costs 80ms each way
     * regardless of who sent; only [PathImpairment.reach] is per direction.
     */
    private fun schedule(
        path: Path,
        direction: LinkDirection,
        source: PlatformBuffer,
        len: Int,
        origin: String,
        withheld: Duration = Duration.ZERO,
        deliver: (PipeDatagram) -> Unit,
    ) {
        val impairment = path.impairment
        val reach = impairment.reach.toward(direction)
        var delay = Duration.ZERO
        synchronized(lock) {
            // Before every drop branch: the size an endpoint chose is a fact about the sender, not
            // about whether this link happened to carry it.
            if (len > path.stats.largestOffered) path.stats.largestOffered = len
            val dark =
                when (reach) {
                    PathReach.Open, is PathReach.HeldUntil -> false
                    PathReach.Dark -> true
                    is PathReach.DarkUntil -> now() < reach.at
                }
            if (dark) {
                // No RNG draws: flipping a blackhole must not shift the seeded sequence around it.
                path.stats.blackholed++
                return
            }
            val oversized =
                when (val mtu = impairment.mtu) {
                    LinkMtu.Unbounded -> false
                    is LinkMtu.Bounded -> len > mtu.bytes
                }
            if (oversized) {
                // Same no-draw discipline as the dark branch, and for the same reason.
                path.stats.oversized++
                return
            }
            val lossRoll = rng.nextDouble()
            val jitterFraction = rng.nextDouble()
            if (lossRoll < impairment.loss) {
                path.stats.dropped++
                return
            }
            val held =
                when (reach) {
                    is PathReach.HeldUntil -> (reach.at - now()).coerceAtLeast(Duration.ZERO)
                    PathReach.Open, PathReach.Dark, is PathReach.DarkUntil -> Duration.ZERO
                }
            delay = maxOf(held, withheld) + impairment.latency + impairment.jitter * jitterFraction
        }
        // Captured only now: a dropped or blackholed datagram allocates nothing, which keeps the ledger
        // counting real deliveries and the seeded sequence independent of allocation.
        val datagram = ledger.capture(source, len, origin)
        if (delay <= Duration.ZERO) {
            deliver(datagram)
        } else {
            scope.launch(start = CoroutineStart.ATOMIC) {
                // ATOMIC, not the default start: a coroutine launched into an ALREADY-cancelled scope
                // never runs its body at all, so a plain launch would strand the datagram it owns with
                // no catch ever firing — exactly one buffer per run, at teardown. ATOMIC guarantees the
                // body begins, `delay` then throws immediately, and the datagram is freed.
                try {
                    delay(delay)
                } catch (t: Throwable) {
                    ledger.release(datagram)
                    throw t
                }
                deliver(datagram)
            }
        }
    }

    private inner class ClientEndpoint(
        private val path: Path,
    ) : UdpChannel {
        override suspend fun receive(buffer: PlatformBuffer): Int {
            val datagram =
                try {
                    path.inbound.receive()
                } catch (_: ClosedReceiveChannelException) {
                    // Terminal park, matching ImpairedPipe.Endpoint: returning here would busy-spin the
                    // driver's reader loop instead of waiting to be cancelled.
                    awaitCancellation()
                }
            buffer.resetForWrite()
            buffer.write(datagram.readable())
            val length = datagram.length
            // The receiver has its own copy now; the pipe's is done.
            ledger.release(datagram)
            return length
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome {
            when (path.socket) {
                SimSocket.Open -> Unit
                SimSocket.Closed -> {
                    path.stats.sentOnClosedSocket++
                    // What `:socket-udp` raises on every backend for a send on a closed sink, typed the way
                    // `sendOutcomeOf` types it.
                    return SendOutcome.Failed(DatagramSendError.Transport(IllegalStateException("sink is closed")))
                }
            }
            val withheld =
                when (val sendReturn = path.impairment.sendReturn) {
                    SendReturn.Immediately -> Duration.ZERO
                    is SendReturn.WithheldUntil -> (sendReturn.at - now()).coerceAtLeast(Duration.ZERO)
                }
            path.stats.sentToServer++
            schedule(path, LinkDirection.Uplink, buffer, len, "client->server@${path.local.port}", withheld) {
                // A closed pipe accepts nothing, so a refused datagram is freed rather than leaked.
                if (toServer.trySend(ServerDatagram(it, path.local)).isFailure) {
                    ledger.release(it)
                } else {
                    path.stats.deliveredToServer++
                }
            }
            // Queued before the wait, so a caller that stops waiting leaves it queued — the platform has it.
            if (withheld > Duration.ZERO) {
                try {
                    delay(withheld)
                } catch (ce: CancellationException) {
                    path.stats.abandonedSends++
                    throw ce
                }
            }
            // A datagram lost on the wire still LEFT this endpoint. Same contract ImpairedPipe states:
            // impairment models the wire, not a send failure, and conflating them would make these
            // suites assert the wrong thing.
            return SendOutcome.Sent
        }

        override fun close() {
            // The socket closes; the path's link stays in the pipe, whose close() tears it down. What was
            // already queued for this socket is discarded with it, as a real socket's receive queue is.
            path.socket = SimSocket.Closed
            path.inbound.close()
            while (true) {
                val queued = path.inbound.tryReceive().getOrNull() ?: break
                path.stats.arrivedAtClosedSocket++
                ledger.release(queued)
            }
        }
    }

    private inner class ServerEndpoint : UdpChannel {
        override suspend fun receive(buffer: PlatformBuffer): Int {
            // The server side is pumped through receiveAtServer() (it needs the source address), so the
            // driver must not own its ingress against this endpoint. Park rather than return, so a
            // harness that wires it wrongly hangs visibly instead of silently feeding quiche datagrams
            // under the wrong recv_info.
            awaitCancellation()
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome {
            val to = (target as? SendTarget.ServerReply)?.to
            val path = synchronized(lock) { to?.let { pathsByKey[it] } ?: pathsByAddr.values.firstOrNull() }
            if (path == null) return SendOutcome.Sent // replied to an address the sim never opened
            path.stats.sentToClient++
            schedule(path, LinkDirection.Downlink, buffer, len, "server->client@${path.local.port}") {
                // A closed pipe or a closed client socket accepts nothing, so a refused datagram is freed
                // rather than leaked.
                if (path.inbound.trySend(it).isFailure) {
                    when (path.socket) {
                        SimSocket.Open -> Unit
                        SimSocket.Closed -> path.stats.arrivedAtClosedSocket++
                    }
                    ledger.release(it)
                } else {
                    path.stats.deliveredToClient++
                }
            }
            return SendOutcome.Sent
        }

        override fun close() = Unit
    }
}
