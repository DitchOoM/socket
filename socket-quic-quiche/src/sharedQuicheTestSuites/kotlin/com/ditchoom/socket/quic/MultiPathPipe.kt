@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.SocketAddressCodec
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
 * [blackhole] is the #447 condition: a path that swallows everything in both directions, so the
 * PATH_CHALLENGE is never answered and validation runs out its RFC 9000 §8.2.4 budget. It is a
 * property of the path rather than of the pipe so one path can die while the others stay healthy —
 * which is what a real handoff looks like and what `ImpairedPipe.blackhole` (whole-pipe) cannot model.
 */
internal data class PathImpairment(
    val latency: Duration = Duration.ZERO,
    val jitter: Duration = Duration.ZERO,
    val loss: Double = 0.0,
    val blackhole: Boolean = false,
)

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
 * A [PathImpairment.blackhole] drop consumes **no** draws, so killing a path mid-run does not shift
 * the seeded sequence of everything around it.
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
     * [QuicheDriver.flushOutgoing] passes that through as [UdpChannel.send]'s `dest`, so routing a reply
     * back to the right client path is a map lookup — the sim's stand-in for a real UDP socket's
     * destination address. A `dest` naming no known path is dropped rather than broadcast: that is a
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
     * Apply [path]'s impairment to one datagram and schedule (or drop) it. Returns whether it will be
     * delivered. The impairment is symmetric — it belongs to the link, not to a direction — so an 80ms
     * path costs 80ms each way regardless of who sent.
     */
    private fun schedule(
        path: Path,
        source: PlatformBuffer,
        len: Int,
        origin: String,
        deliver: (PipeDatagram) -> Unit,
    ) {
        val impairment = path.impairment
        var delay = Duration.ZERO
        synchronized(lock) {
            if (impairment.blackhole) {
                // No RNG draws: flipping a blackhole must not shift the seeded sequence around it.
                path.stats.blackholed++
                return
            }
            val lossRoll = rng.nextDouble()
            val jitterFraction = rng.nextDouble()
            if (lossRoll < impairment.loss) {
                path.stats.dropped++
                return
            }
            delay = impairment.latency + impairment.jitter * jitterFraction
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
            dest: PathKey?,
        ): SendOutcome {
            path.stats.sentToServer++
            schedule(path, buffer, len, "client->server@${path.local.port}") {
                // A closed pipe accepts nothing, so a refused datagram is freed rather than leaked.
                if (toServer.trySend(ServerDatagram(it, path.local)).isFailure) ledger.release(it)
            }
            // A datagram lost on the wire still LEFT this endpoint. Same contract ImpairedPipe states:
            // impairment models the wire, not a send failure, and conflating them would make these
            // suites assert the wrong thing.
            return SendOutcome.Sent
        }

        override fun close() {
            // Path lifetime == pipe lifetime; MultiPathPipe.close() tears everything down.
        }
    }

    private inner class ServerEndpoint : UdpChannel {
        override suspend fun receive(buffer: PlatformBuffer): Int {
            // The server side is pumped through receiveAtServer() (it needs the source address), so the
            // driver must not be run in clientMode against this endpoint. Park rather than return, so a
            // harness that wires it wrongly hangs visibly instead of silently feeding quiche datagrams
            // under the wrong recv_info.
            awaitCancellation()
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            dest: PathKey?,
        ): SendOutcome {
            val path = synchronized(lock) { dest?.let { pathsByKey[it] } ?: pathsByAddr.values.firstOrNull() }
            if (path == null) return SendOutcome.Sent // replied to an address the sim never opened
            path.stats.sentToClient++
            schedule(path, buffer, len, "server->client@${path.local.port}") {
                // A closed pipe accepts nothing, so a refused datagram is freed rather than leaked.
                if (path.inbound.trySend(it).isFailure) ledger.release(it)
            }
            return SendOutcome.Sent
        }

        override fun close() = Unit
    }
}
