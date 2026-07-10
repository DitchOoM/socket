package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
) {
    private val rng = Random(config.seed)
    private val lock = Any()

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

    private val toServer = Channel<ByteArray>(Channel.UNLIMITED)
    private val toClient = Channel<ByteArray>(Channel.UNLIMITED)

    val clientEndpoint: UdpChannel = Endpoint(inbound = toClient, outbound = toServer, stats = clientStats)
    val serverEndpoint: UdpChannel = Endpoint(inbound = toServer, outbound = toClient, stats = serverStats)

    fun close() {
        toServer.close()
        toClient.close()
    }

    private inner class Endpoint(
        private val inbound: Channel<ByteArray>,
        private val outbound: Channel<ByteArray>,
        private val stats: SideStats,
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
            val bb = (buffer.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
            bb.clear()
            bb.put(datagram)
            return datagram.size
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            dest: PathKey?,
        ) {
            val bb = (buffer.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
            bb.clear()
            bb.limit(len)
            val copy = ByteArray(len)
            bb.get(copy)

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

                val dropped = dropRoll < config.loss
                val duplicated = !dropped && dupRoll < config.duplicateProb
                decisionTrace.add(Decision(dropped, duplicated, reorderSlots))
                if (dropped) {
                    stats.dropped++
                    return@synchronized
                }
                deliverPrimary = true
                deliverDuplicate = duplicated
                if (duplicated) stats.duplicated++
                deliveryDelay = config.latency + config.jitter * jitterFraction + reorderSlots.milliseconds
            }
            if (deliverPrimary) deliver(copy, deliveryDelay)
            if (deliverDuplicate) deliver(copy.copyOf(), deliveryDelay + 1.milliseconds)
        }

        private fun deliver(
            bytes: ByteArray,
            after: Duration,
        ) {
            stats.enqueued++
            if (after <= Duration.ZERO) {
                // Synchronous enqueue keeps the zero-delay pipe strictly FIFO.
                outbound.trySend(bytes)
            } else {
                scope.launch {
                    delay(after)
                    outbound.trySend(bytes)
                }
            }
        }

        override fun close() {
            // Endpoint lifetime == pipe lifetime; the sim tears both down via ImpairedPipe.close().
        }
    }
}
