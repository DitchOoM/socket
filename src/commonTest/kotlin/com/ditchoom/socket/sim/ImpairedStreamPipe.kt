package com.ditchoom.socket.sim

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.testkit.fault.ByteEdit
import com.ditchoom.socket.testkit.fault.Fault
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.testkit.fault.ImpairmentEngine
import com.ditchoom.socket.testkit.fault.UnitDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The TCP Tier-A substrate (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §2/§3, P3): a pair of in-memory
 * [ByteStream] endpoints — [clientEndpoint] and [serverEndpoint] — joined through a testkit
 * [ImpairmentEngine], with **no OS sockets anywhere**. It is the byte-stream analogue of the UDP
 * `ImpairedDatagramPipe`: the same neutral [FaultSchedule]/[ImpairmentEngine] drives it, so a schedule
 * reproduces bit-for-bit and the identical schedule can drive the Tier-C `toxiproxy` wire proxy for
 * A⇄C parity.
 *
 * **The TCP unit is a byte-run** (RFC §11 decision 3): each [ByteStream.write] call is one unit, in send
 * order per direction. Byte-run is the canonical unit precisely because toxiproxy cannot see TCP
 * segments — keeping the unit coarse is what lets Tier-A and Tier-C stay in parity.
 *
 * **The action set is the TCP column of RFC §4**: `delay`, `drop`, `corrupt`, `resetAfter`, and
 * `blackhole` (which is `drop` over a [com.ditchoom.socket.testkit.fault.UnitSelector.From]). The two
 * datagram-only faults — `reorder` and `duplicate` — have **no TCP analogue** (a reliable, ordered byte
 * stream never reorders, and a byte-run duplicate is not expressible on the wire), so a schedule
 * carrying either is rejected at construction. That is the inverse of the UDP pipe, which instead
 * rejects `resetAfter` (a datagram transport has no connection to tear down) — each pipe refuses only
 * the faults with no meaning for its transport, so there are no impossible states.
 *
 * **Reset is connection-level.** A [com.ditchoom.socket.testkit.fault.Termination.ResetAfterUnits] on
 * *either* direction tears the whole connection down hard once that many units have crossed: both
 * endpoints' subsequent reads observe [ReadResult.Reset] (after any already-buffered units drain) and
 * subsequent writes throw [SocketClosedException.ConnectionReset], mirroring a peer RST.
 *
 * Threading: like a real socket, confine each endpoint's `read`/`write` to one coroutine. Under
 * `runTest` everything runs on the single test dispatcher, so the plain counters/flags below are safe.
 *
 * @param clientToServer schedule for the client→server direction.
 * @param serverToClient schedule for the server→client direction (default [FaultSchedule.CLEAN]).
 * @param scope the coroutine scope whose dispatcher schedules delayed deliveries — pass the `runTest`
 *   scope so virtual time drives it.
 * @param config supplies the buffer factory and the read/write deadline policies each endpoint adopts.
 */
class ImpairedStreamPipe(
    clientToServer: FaultSchedule,
    serverToClient: FaultSchedule = FaultSchedule.CLEAN,
    private val scope: CoroutineScope,
    private val config: TransportConfig = TransportConfig(),
) {
    init {
        requireStreamExpressible(clientToServer, "clientToServer")
        requireStreamExpressible(serverToClient, "serverToClient")
    }

    /** Per-direction observable counters (for test assertions). */
    class DirectionStats {
        var sent: Int = 0
            internal set
        var dropped: Int = 0
            internal set

        /** Byte-runs actually enqueued toward the peer — excludes drops (TCP never duplicates). */
        var delivered: Int = 0
            internal set
    }

    val clientToServerStats: DirectionStats = DirectionStats()
    val serverToClientStats: DirectionStats = DirectionStats()

    /** True once a schedule [com.ditchoom.socket.testkit.fault.Termination] has torn the connection down. */
    var wasReset: Boolean = false
        private set

    // Unbounded so a write never suspends on a slow reader; the engine controls delivery ordering under
    // virtual time, not backpressure.
    private val toServer = Channel<ReadBuffer>(Channel.UNLIMITED)
    private val toClient = Channel<ReadBuffer>(Channel.UNLIMITED)

    val clientEndpoint: ByteStream =
        Endpoint(
            inbound = toClient,
            outbound = toServer,
            engine = ImpairmentEngine(clientToServer),
            stats = clientToServerStats,
        )

    val serverEndpoint: ByteStream =
        Endpoint(
            inbound = toServer,
            outbound = toClient,
            engine = ImpairmentEngine(serverToClient),
            stats = serverToClientStats,
        )

    /** Close both directions cleanly (peer EOF); parked reads then observe [ReadResult.End]. */
    fun close() {
        toServer.close()
        toClient.close()
    }

    // Hard connection teardown (RST). Idempotent; closes both directions with a reset cause so reads
    // return ReadResult.Reset once buffered units drain and writes throw ConnectionReset.
    private fun triggerReset() {
        if (wasReset) return
        wasReset = true
        val cause = SocketClosedException.ConnectionReset("connection reset by schedule termination")
        toServer.close(cause)
        toClient.close(cause)
    }

    private fun resetException() = SocketClosedException.ConnectionReset("connection reset by schedule termination")

    private inner class Endpoint(
        private val inbound: Channel<ReadBuffer>,
        private val outbound: Channel<ReadBuffer>,
        private val engine: ImpairmentEngine,
        private val stats: DirectionStats,
    ) : ByteStream {
        override val isOpen: Boolean get() = !wasReset && !inbound.isClosedForReceive
        override val readPolicy: ReadPolicy = config.readPolicy
        override val writePolicy: WritePolicy = config.writePolicy

        override suspend fun read(deadline: Duration): ReadResult =
            try {
                val buffer =
                    if (deadline.isFinite()) {
                        withTimeout(deadline) { inbound.receive() }
                    } else {
                        inbound.receive()
                    }
                ReadResult.Data(buffer)
            } catch (_: SocketClosedException.ConnectionReset) {
                ReadResult.Reset
            } catch (_: ClosedReceiveChannelException) {
                ReadResult.End
            } catch (e: CancellationException) {
                if (inbound.isClosedForReceive) ReadResult.End else throw e
            }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            if (wasReset) throw resetException()

            val index = engine.nextExpectedIndex
            if (engine.terminatesAt(index)) {
                // This unit sits at/after the reset boundary — the connection is already gone.
                triggerReset()
                throw resetException()
            }

            val remaining = buffer.remaining()
            val bytes = buffer.readByteArray(remaining) // consuming, per the ByteStream write contract
            stats.sent++
            when (val decision = engine.decide()) {
                is UnitDecision.Dropped -> stats.dropped++
                is UnitDecision.Delivered ->
                    decision.copies.forEach { copy ->
                        stats.delivered++
                        deliver(applyEdits(bytes, copy.edits), copy.afterDelay)
                    }
            }

            // Proactively RST once `count` units have crossed, so a reader that stops after the last
            // delivered unit still observes the reset ("tear down after N units have crossed").
            if (engine.terminatesAt(engine.nextExpectedIndex)) triggerReset()
            return BytesWritten(remaining)
        }

        private fun deliver(
            bytes: ByteArray,
            after: Duration,
        ) {
            if (after <= Duration.ZERO) {
                // Zero-delay: enqueue synchronously so the clean pipe is strictly FIFO.
                outbound.trySend(config.bufferFactory.wrap(bytes))
            } else {
                scope.launch {
                    delay(after)
                    outbound.trySend(config.bufferFactory.wrap(bytes))
                }
            }
        }

        override suspend fun close() {
            // Half-close semantics like a real socket: stop reading, signal EOF to the peer.
            inbound.cancel()
            outbound.close()
        }
    }

    private companion object {
        /** Apply the schedule's byte edits to a fresh copy; an offset past the run is a no-op. */
        fun applyEdits(
            source: ByteArray,
            edits: List<ByteEdit>,
        ): ByteArray {
            if (edits.isEmpty()) return source
            val out = source.copyOf()
            for (edit in edits) {
                if (edit.offset in out.indices) out[edit.offset] = (out[edit.offset].toInt() xor edit.flipMask).toByte()
            }
            return out
        }

        /** Reject the datagram-only faults that have no TCP byte-stream analogue (RFC §4 TCP column). */
        fun requireStreamExpressible(
            schedule: FaultSchedule,
            direction: String,
        ) {
            schedule.faults.forEach { scheduled ->
                require(scheduled.fault !is Fault.Reorder) {
                    "$direction: reorder(...) has no TCP analogue — a reliable, ordered byte stream never " +
                        "reorders. Reordering is a UDP/QUIC datagram fault."
                }
                require(scheduled.fault !is Fault.Duplicate) {
                    "$direction: duplicate(...) has no TCP analogue — a byte-run duplicate is not expressible " +
                        "on the wire. Duplication is a UDP/QUIC datagram fault."
                }
            }
        }
    }
}
