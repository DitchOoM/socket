@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // Channel.isEmpty, as production reads it

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A read that parks always wakes: every state quiche is left holding bytes in is signalled, handed off or
 * marked, so a read that decides not to ask the loop (`StreamSlot.readNeedsQuicheAnswer`) is never
 * stranded.
 *
 * ## The model
 *
 * quiche holds a number of bytes. The **driver loop** is serial (one lock). A turn receives a datagram
 * (two more bytes) and then sweeps as `QuicheDriver.discoverNewStreams` does for a readable stream: take
 * the advert of a parked read and, if there is one, hand it a chunk of the size it asked for
 * (`StreamSlot.deliverHandedOff`), then signal. The **reader** follows `DriverStreamAdapter.readNext`: take
 * what is queued, else decide whether to ask (`readNeedsQuicheAnswer`); asking drains up to a chunk under
 * the loop lock and the loop's sweep after that command signals when bytes remain; otherwise it parks as
 * `StreamSlot.awaitData` does (advertise, wake if signalled, withdraw) and looks at the queue. A read that
 * wakes with nothing queued asks. The read reports whether it ended **stranded**: parked, not woken, nothing
 * handed to it, while quiche holds bytes no later sweep will announce. Parking with quiche empty is legal.
 *
 * One chunk is one byte and a datagram carries two, so the chunk that fills the request leaves a byte in
 * quiche and nothing but the mark says so: [WithoutTheMarkLincheckTest] drops `deliverHandedOff`'s mark and
 * MUST fail.
 */
abstract class NoStrandedBytesModel(
    private val markFilledRequests: Boolean,
) {
    private val slot = StreamSlot(QuicStreamId(0))
    private val loop = Any()
    private val chunk: ReadBuffer = BufferFactory.Default.allocate(1)
    private var holds = 0 // guarded by loop

    @Operation(nonParallelGroup = DRIVER)
    fun datagramTurn() {
        synchronized(loop) {
            holds += 2
            val size = slot.takeWaitingRead()
            if (size > 0) {
                val taken = minOf(size, holds)
                holds -= taken
                if (markFilledRequests) {
                    slot.deliverHandedOff(chunk, fin = false, requested = size, bytesRead = taken)
                } else {
                    slot.deliverDrained(chunk, fin = false)
                }
            }
            slot.dataSignal.trySend(Unit)
        }
    }

    @Operation(nonParallelGroup = READER)
    fun read(): Boolean {
        var ask = slot.readNeedsQuicheAnswer()
        repeat(3) {
            if (take()) return false
            if (ask) {
                synchronized(loop) {
                    if (holds > 0) {
                        holds -= minOf(SIZE, holds)
                        // The sweep that follows the StreamRecv command.
                        if (holds > 0) slot.dataSignal.trySend(Unit)
                        return false
                    }
                }
            }
            slot.advertiseWaitingRead(SIZE)
            val woke = slot.dataSignal.tryReceive().isSuccess
            slot.withdrawWaitingRead()
            if (take()) return false
            if (!woke) return synchronized(loop) { holds > 0 }
            ask = true // woken with nothing queued: ask, as readNext does
        }
        return false
    }

    private fun take(): Boolean =
        (
            slot.queuedOrLatchedVerdict {
                slot.takeQueued(handBack = { slot.returnHandOff() })?.let { ReadResult.Data(it) }
            } as? ReadResult.Data
        ) != null

    companion object {
        const val DRIVER = "driver"
        const val READER = "reader"
        private const val SIZE = 1
    }
}

class StrandedSpec {
    fun datagramTurn() = Unit

    fun read(): Boolean = false
}

class StreamSlotNoStrandedBytesLincheckTest : NoStrandedBytesModel(markFilledRequests = true) {
    @Test
    fun aReadThatParksAlwaysWakes() {
        strandedOptions().check(this::class)
    }
}

/** The control arm: a hand-off that does not mark a filled request. It MUST fail. */
class WithoutTheMarkLincheckTest : NoStrandedBytesModel(markFilledRequests = false) {
    @Test
    fun aFilledRequestWithoutTheMarkStrandsTheNextRead() {
        assertFailsWith<LincheckAssertionError>(
            "the model survived without the mark: either Lincheck is inert here or the model no longer reaches the stranding.",
        ) {
            strandedOptions().check(this::class)
        }
    }
}

private fun strandedOptions(): ModelCheckingOptions =
    ModelCheckingOptions()
        .sequentialSpecification(StrandedSpec::class.java)
        .addCustomScenario {
            parallel {
                thread {
                    actor(NoStrandedBytesModel::datagramTurn)
                    actor(NoStrandedBytesModel::datagramTurn)
                }
                thread {
                    actor(NoStrandedBytesModel::read)
                    actor(NoStrandedBytesModel::read)
                    actor(NoStrandedBytesModel::read)
                }
            }
        }.threads(2)
        .actorsPerThread(3)
        .actorsBefore(0)
        .iterations(STRANDED_SCENARIOS)
        .invocationsPerIteration(STRANDED_INVOCATIONS)

private val STRANDED_SCENARIOS = LincheckBudget.scaled(10)
private val STRANDED_INVOCATIONS = LincheckBudget.scaled(1_000)
