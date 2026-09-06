package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Seeded impairment model for one [ImpairedPipe] (W4, RFC_DETERMINISTIC_SIMULATION.md §4 Tier B).
 *
 * A single `kotlin.random.Random(seed)` drives ALL impairment decisions, in datagram-arrival order
 * at the pipe, with a **fixed number of draws per datagram** (drop, duplicate, reorder-slot,
 * jitter-fraction — always all four) so the decision sequence is a pure function of the seed and
 * the arrival order, never of which impairments are enabled or which branch an earlier datagram took.
 *
 * @param loss probability a datagram is silently dropped.
 * @param reorderWindow when > 0, each datagram is held for `rng.nextInt(reorderWindow + 1)` extra
 *   milliseconds — unequal hold times between neighbouring datagrams are what produce real
 *   reordering, without ever stalling a lone in-flight packet the way a fill-the-buffer model would.
 * @param duplicateProb probability a datagram is delivered twice (the duplicate trails by 1 ms).
 * @param latency fixed one-way delivery delay.
 * @param jitter additional uniformly-random delay in `[0, jitter)`.
 */
internal class ImpairmentConfig(
    val seed: Long,
    val loss: Double = 0.0,
    val reorderWindow: Int = 0,
    val duplicateProb: Double = 0.0,
    val latency: Duration = Duration.ZERO,
    val jitter: Duration = Duration.ZERO,
    /**
     * Bisection seam: datagram indices (in RNG-draw order, i.e. [ImpairedPipe.decisions] order) whose
     * seeded drop decision is overridden. The RNG draws are still consumed — the override replaces
     * the *decision*, not the *draw* — so every other datagram keeps its seeded fate and a single
     * decision can be pinned in isolation. A datagram in both sets is delivered.
     */
    val forceDeliver: Set<Int> = emptySet(),
    val forceDrop: Set<Int> = emptySet(),
)

/**
 * A pair of in-memory [UdpChannel] endpoints joined through the [ImpairmentConfig] model — the
 * Tier-B substrate: a real quiche client on [clientEndpoint] and a real quiche server on
 * [serverEndpoint] exchange their actual datagrams with seeded loss/reorder/duplication/delay and
 * no OS sockets anywhere.
 *
 * Delivery scheduling uses `delay()` on [scope]'s dispatcher, so under `runTest` virtual time
 * controls it; with zero computed delay the datagram is enqueued synchronously in send order (the
 * pipe is then perfectly FIFO). Non-zero delays are delivered by concurrently-launched coroutines,
 * so overlapping delays reorder exactly as the model intends.
 *
 * [blackhole] simulates total connectivity loss (e.g. after handshake): every datagram is counted
 * as sent+dropped WITHOUT consuming any RNG draws, so flipping it mid-run does not shift the seeded
 * decision sequence of datagrams delivered before/after.
 */
internal class ImpairedPipe(
    private val config: ImpairmentConfig,
    private val scope: CoroutineScope,
    private val ledger: DatagramLedger,
) {
    private val rng = Random(config.seed)
    private val lock = SynchronizedObject()

    @Volatile
    var blackhole: Boolean = false

    /** Per-side counters. [sent] = send() calls; [enqueued] = copies scheduled toward the peer (incl. duplicates). */
    internal class SideStats {
        @Volatile var sent = 0

        @Volatile var dropped = 0

        @Volatile var duplicated = 0

        @Volatile var enqueued = 0
    }

    val clientStats = SideStats()
    val serverStats = SideStats()

    /**
     * The impairment decision trace, in RNG-draw order: one entry per datagram that consumed draws
     * (blackhole drops consume none). Same seed → identical trace prefix across runs by construction;
     * [SemanticSimTests] uses it as the determinism invariant that survives real-dispatcher timing.
     */
    internal data class Decision(
        val dropped: Boolean,
        val duplicated: Boolean,
        val reorderSlots: Int,
    )

    private val decisionTrace = ArrayList<Decision>()

    fun decisions(): List<Decision> = synchronized(lock) { decisionTrace.toList() }

    /**
     * One row per datagram that consumed draws, with what [Decision] deliberately leaves out: which
     * side sent it, its size, its first byte (packet type), and the virtual instant it was offered to
     * the pipe (−1 off a test scheduler). Diagnostics only — [Decision] stays the determinism invariant.
     */
    internal data class Observation(
        val index: Int,
        val side: String,
        val len: Int,
        val dropped: Boolean,
        val seededDrop: Boolean,
        val atMs: Long,
        /**
         * The datagram's first byte — enough to classify the packet type (RFC 9000 §17.2), which is
         * all any consumer ever read off the old `bytes` array. Keeping a byte instead of a per-
         * datagram copy keeps the diagnostic trace off the allocation path entirely.
         */
        val firstByte: Byte,
    )

    private val observationTrace = ArrayList<Observation>()

    fun observations(): List<Observation> = synchronized(lock) { observationTrace.toList() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun virtualNowMs(): Long = scope.coroutineContext[kotlinx.coroutines.test.TestCoroutineScheduler]?.currentTime ?: -1L

    // onUndeliveredElement: a datagram the channel accepted but no receiver ever got (a reader
    // cancelled while suspended in receive()) still has to be freed by somebody.
    private val toServer = Channel<PipeDatagram>(Channel.UNLIMITED) { ledger.release(it) }
    private val toClient = Channel<PipeDatagram>(Channel.UNLIMITED) { ledger.release(it) }

    val clientEndpoint: UdpChannel = Endpoint(inbound = toClient, outbound = toServer, stats = clientStats, side = "C")
    val serverEndpoint: UdpChannel = Endpoint(inbound = toServer, outbound = toClient, stats = serverStats, side = "S")

    fun close() {
        toServer.close()
        toClient.close()
        // Anything still queued was captured and will never be delivered; free it here or it is a leak
        // the ledger will (correctly) report against this pipe.
        drain(toServer)
        drain(toClient)
    }

    private fun drain(channel: Channel<PipeDatagram>) {
        while (true) {
            val result = channel.tryReceive()
            val datagram = result.getOrNull() ?: return
            ledger.release(datagram)
        }
    }

    private inner class Endpoint(
        private val inbound: Channel<PipeDatagram>,
        private val outbound: Channel<PipeDatagram>,
        private val stats: SideStats,
        private val side: String,
    ) : UdpChannel {
        override suspend fun receive(buffer: PlatformBuffer): Int {
            val datagram =
                try {
                    inbound.receive()
                } catch (_: ClosedReceiveChannelException) {
                    // Terminal park (see AppleNwUdpChannel precedent in QuicheDriver.udpReaderLoop's
                    // docs): a permanently-dead channel must suspend until the driver cancels the
                    // reader — returning/throwing here would busy-spin the reader loop.
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
            var deliverPrimary = false
            var deliverDuplicate = false
            var deliveryDelay = Duration.ZERO
            synchronized(lock) {
                stats.sent++
                if (blackhole) {
                    stats.dropped++
                    return@synchronized
                }
                // Fixed draw count per datagram — see ImpairmentConfig docs.
                val dropRoll = rng.nextDouble()
                val dupRoll = rng.nextDouble()
                val reorderSlots = if (config.reorderWindow > 0) rng.nextInt(config.reorderWindow + 1) else 0
                val jitterFraction = rng.nextDouble()

                val seededDrop = dropRoll < config.loss
                val index = decisionTrace.size
                val dropped =
                    when {
                        index in config.forceDeliver -> false
                        index in config.forceDrop -> true
                        else -> seededDrop
                    }
                val duplicated = !dropped && dupRoll < config.duplicateProb
                decisionTrace.add(Decision(dropped, duplicated, reorderSlots))
                observationTrace.add(Observation(index, side, len, dropped, seededDrop, virtualNowMs(), firstByteOf(buffer, len)))
                if (dropped) {
                    stats.dropped++
                    return@synchronized
                }
                deliverPrimary = true
                deliverDuplicate = duplicated
                if (duplicated) stats.duplicated++
                deliveryDelay = config.latency + config.jitter * jitterFraction + reorderSlots.milliseconds
            }
            // Captured only now: a dropped or blackholed datagram allocates nothing, which keeps the
            // ledger counting real deliveries and the seeded sequence independent of allocation. A
            // duplicate is a SECOND capture — each copy is freed by whoever receives it.
            if (deliverPrimary) deliver(ledger.capture(buffer, len, "impaired-$side"), deliveryDelay)
            if (deliverDuplicate) deliver(ledger.capture(buffer, len, "impaired-$side-dup"), deliveryDelay + 1.milliseconds)
            // Impairment models the WIRE: a dropped or blackholed datagram left this endpoint
            // successfully and vanished in transit. That is deliberately NOT a send failure — the
            // distinction is the whole point of SendOutcome, and conflating them here would make the
            // impairment suite assert the wrong contract.
            return SendOutcome.Sent
        }

        private fun deliver(
            datagram: PipeDatagram,
            after: Duration,
        ) {
            stats.enqueued++
            if (after <= Duration.ZERO) {
                // Synchronous enqueue keeps the zero-delay pipe strictly FIFO.
                enqueue(datagram)
            } else {
                scope.launch(start = CoroutineStart.ATOMIC) {
                    // ATOMIC, not the default start: a coroutine launched into an ALREADY-cancelled
                    // scope never runs its body at all, so a plain launch would strand the datagram it
                    // owns with no catch ever firing. ATOMIC guarantees the body begins, `delay` then
                    // throws immediately, and the datagram is freed.
                    try {
                        delay(after)
                    } catch (t: Throwable) {
                        ledger.release(datagram)
                        throw t
                    }
                    enqueue(datagram)
                }
            }
        }

        /** A closed pipe accepts nothing, so a refused datagram is freed rather than leaked. */
        private fun enqueue(datagram: PipeDatagram) {
            if (outbound.trySend(datagram).isFailure) ledger.release(datagram)
        }

        override fun close() {
            // Endpoint lifetime == pipe lifetime; the sim tears both down via ImpairedPipe.close().
        }
    }
}
