@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.testkit.fault.ByteEdit
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.testkit.fault.ImpairmentEngine
import com.ditchoom.socket.testkit.fault.Termination
import com.ditchoom.socket.testkit.fault.UnitDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The UDP Tier-A substrate (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §2, P1): a pair of in-memory
 * [DatagramChannel] endpoints — [clientEndpoint] and [serverEndpoint] — joined through a testkit
 * [ImpairmentEngine], with **no OS sockets anywhere**. Each datagram a side sends is a transport
 * *unit*; the engine decides its fate (drop / delay / duplicate / corrupt / reorder) from the
 * [FaultSchedule] for that direction, and delivery is scheduled on [scope]'s dispatcher so that under
 * `runTest` virtual time the whole exchange is deterministic and wall-clock-free.
 *
 * This is the datagram analogue of the QUIC Tier-B `ImpairedPipe`, but built on the **public**
 * [DatagramChannel] type and driven by the neutral [FaultSchedule]/[ImpairmentEngine] rather than a
 * probabilistic `ImpairmentConfig` — so the same schedule reproduces bit-for-bit, and the identical
 * schedule can drive the Tier-C `udp-toxi` relay for A⇄C parity.
 *
 * **Direction is explicit.** [clientToServer] impairs datagrams the client sends; [serverToClient]
 * impairs datagrams the server sends (default clean). Each direction owns its own [ImpairmentEngine]
 * (its own seeded draw sequence), so the two never interfere.
 *
 * **UDP has no connection to reset.** A schedule carrying [Termination.ResetAfterUnits] is rejected at
 * construction — the datagram analogue of a reset is a blackhole (`blackholeFrom(k)`), which the engine
 * already expresses. That keeps a TCP-only fault from silently no-op'ing here (no impossible states).
 *
 * Threading: like a real [DatagramChannel], confine each endpoint's `send`/`receive` to one coroutine.
 * Under `runTest` everything runs on the single test dispatcher, so the plain counters below are safe.
 *
 * @param clientToServer schedule for client→server datagrams.
 * @param serverToClient schedule for server→client datagrams (default [FaultSchedule.CLEAN]).
 * @param scope the coroutine scope whose dispatcher schedules delayed/duplicated deliveries — pass the
 *   `runTest` scope so virtual time drives it.
 * @param bufferFactory allocates the payload buffer each delivered datagram carries; the default heap
 *   factory is fine for an in-memory pipe (no syscall needs native memory).
 */
@ExperimentalDatagramApi
class ImpairedDatagramPipe(
    clientToServer: FaultSchedule,
    serverToClient: FaultSchedule = FaultSchedule.CLEAN,
    private val scope: CoroutineScope,
    private val bufferFactory: BufferFactory = BufferFactory.Default,
) {
    init {
        require(clientToServer.termination is Termination.None) {
            "UDP has no connection to reset; express total loss with blackholeFrom(...) instead of resetAfter(...)"
        }
        require(serverToClient.termination is Termination.None) {
            "UDP has no connection to reset; express total loss with blackholeFrom(...) instead of resetAfter(...)"
        }
    }

    /** Per-direction observable counters (for test assertions). */
    class DirectionStats {
        var sent: Int = 0
            internal set
        var dropped: Int = 0
            internal set

        /** Copies actually enqueued toward the peer — includes duplicates, excludes drops. */
        var delivered: Int = 0
            internal set
    }

    val clientToServerStats: DirectionStats = DirectionStats()
    val serverToClientStats: DirectionStats = DirectionStats()

    // Synthetic, non-resolving peer identities so a received Datagram carries a stable, realistic peer
    // (the pipe is a fixed client↔server pair; ofLiteral does no DNS).
    private val clientAddress: SocketAddress = SocketAddress.ofLiteral("127.0.0.1", 40001)
    private val serverAddress: SocketAddress = SocketAddress.ofLiteral("127.0.0.1", 40002)

    // Unbounded so a send never suspends on a slow reader; virtual-time delivery ordering is what the
    // engine controls, not backpressure.
    private val toServer = Channel<PlatformBuffer>(Channel.UNLIMITED)
    private val toClient = Channel<PlatformBuffer>(Channel.UNLIMITED)

    val clientEndpoint: DatagramChannel =
        Endpoint(
            inbound = toClient,
            outbound = toServer,
            engine = ImpairmentEngine(clientToServer),
            stats = clientToServerStats,
            peer = serverAddress,
        )

    val serverEndpoint: DatagramChannel =
        Endpoint(
            inbound = toServer,
            outbound = toClient,
            engine = ImpairmentEngine(serverToClient),
            stats = serverToClientStats,
            peer = clientAddress,
        )

    /** Close both directions; parked [DatagramChannel.receive] calls then observe [DatagramReadResult.Closed]. */
    fun close() {
        toServer.close()
        toClient.close()
    }

    private inner class Endpoint(
        private val inbound: Channel<PlatformBuffer>,
        private val outbound: Channel<PlatformBuffer>,
        private val engine: ImpairmentEngine,
        private val stats: DirectionStats,
        private val peer: SocketAddress,
    ) : DatagramChannel {
        override val isOpen: Boolean get() = !outbound.isClosedForSend
        override val localAddress: SocketAddress? = null
        override val maxWritableSize: Int = MAX_UDP_PAYLOAD
        override val capabilities: DatagramCapabilities = DatagramCapabilities.None

        override suspend fun receive(): DatagramReadResult {
            val result = inbound.receiveCatching()
            val payload = result.getOrNull() ?: return DatagramReadResult.Closed()
            return DatagramReadResult.Received(Datagram(payload = payload, peer = peer))
        }

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress?,
            options: DatagramSendOptions,
        ) {
            // slice(): a non-consuming view (buffer-flow's send-does-not-consume contract), copied out so
            // the delivered datagram owns its bytes and any corruption edit mutates only the copy.
            val slice = payload.slice()
            val bytes = slice.readByteArray(slice.remaining())

            stats.sent++
            when (val decision = engine.decide()) {
                is UnitDecision.Dropped -> stats.dropped++
                is UnitDecision.Delivered ->
                    decision.copies.forEach { copy ->
                        stats.delivered++
                        deliver(applyEdits(bytes, copy.edits), copy.afterDelay)
                    }
            }
        }

        private fun deliver(
            bytes: ByteArray,
            after: Duration,
        ) {
            if (after <= Duration.ZERO) {
                // Zero-delay: enqueue synchronously so the clean pipe is strictly FIFO.
                outbound.trySend(bufferFactory.wrap(bytes))
            } else {
                scope.launch {
                    delay(after)
                    outbound.trySend(bufferFactory.wrap(bytes))
                }
            }
        }

        override fun close() {
            // Endpoint lifetime == pipe lifetime; tear both down via ImpairedDatagramPipe.close().
        }
    }

    private companion object {
        /** 65535 − 8 (UDP) − 20 (IPv4). The datagram staging ceiling. */
        const val MAX_UDP_PAYLOAD = 65507

        /** Apply the schedule's byte edits to a fresh copy; an offset past the payload is a no-op. */
        fun applyEdits(
            source: ByteArray,
            edits: List<ByteEdit>,
        ): ByteArray {
            val out = source.copyOf()
            for (edit in edits) {
                if (edit.offset in out.indices) out[edit.offset] = (out[edit.offset].toInt() xor edit.flipMask).toByte()
            }
            return out
        }
    }
}
