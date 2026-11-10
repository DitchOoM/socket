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
 * [StreamSlot]'s read-side latch, model checked as the driver loop and the reading coroutine use it.
 *
 * ## The two sides
 *
 * The **driver loop** (one thread) hands the slot what quiche already accepted when the connection
 * tears down: chunks, then the stream's FIN ([StreamSlot.deliverDrained]), or the peer's RESET_STREAM
 * ([StreamSlot.latchReset]). The **reader** (another thread) asks the slot what to return when quiche
 * has nothing more for it — a `Done` answer, or the top of a read: the next queued chunk, else the
 * latched verdict, else wait ([StreamSlot.queuedOrLatchedVerdict] over [StreamSlot.takeQueued]), and
 * settles the stream's read state as the read returns.
 *
 * [StreamSlot.holdsUnread] is deliberately not an operation here. A read that finds the queue empty
 * marks the stream handed-off for an instant before giving the mark back, so the query can over-report
 * mid-read; that is by design (the give-back re-evaluates the unread-at-close report), but it is not
 * linearizable against a plain queue, and the first run of this test reported exactly that schedule.
 *
 * ## The specification
 *
 * [StreamSlotSpec]: a queue and a latch. A read returns the oldest chunk while there is one, then the
 * verdict, and waits while the stream is open. The verdict latches once; the first latch wins. So a
 * read never reports End or Reset while a chunk the driver delivered is still queued — that would be
 * #318, the bytes quiche accepted lost behind a clean-looking end.
 *
 * ## Why a control arm
 *
 * A conformance test that has only ever run against the code believed correct proves nothing about
 * its own sensitivity (see `LincheckHarnessProbe` in the root module). [QueueFirstVerdictLincheckTest]
 * is the read decision exactly as `DriverStreamAdapter`'s `Done` branch made it before this test
 * existed — queue first, verdict second — and it MUST fail.
 */
abstract class StreamSlotModel {
    protected val slot = StreamSlot(QuicStreamId(0))

    // Allocated up front, so the model checker explores the slot and not the buffer allocator.
    private val chunks: List<ReadBuffer> = List(MAX_CHUNKS) { BufferFactory.Default.allocate(1) }
    private var delivered = 0

    private fun nextChunk(): ReadBuffer = chunks[delivered++]

    private fun label(chunk: ReadBuffer): String = "chunk#${chunks.indexOfFirst { it === chunk }}"

    @Operation(nonParallelGroup = DRIVER)
    fun drainChunk(): Boolean = slot.deliverDrained(nextChunk(), fin = false)

    @Operation(nonParallelGroup = DRIVER)
    fun drainLastChunkWithFin(): Boolean = slot.deliverDrained(nextChunk(), fin = true)

    @Operation(nonParallelGroup = DRIVER)
    fun drainReset(): Boolean = slot.latchReset(QuicAppErrorCode(RESET_CODE))

    /**
     * The reader's own `StreamRecv` came back with the FIN (coalesced with its data, or bare). Unit, as
     * production ignores the latch's answer: which of two FIN latches "won" is not observable, and a
     * Boolean here made the first run of this test report two FINs as a violation.
     */
    @Operation(nonParallelGroup = READER)
    fun readerSawFin() {
        slot.latchFin()
    }

    /** A read that quiche has nothing more for right now; settles the read state the way `streamRead` does. */
    @Operation(nonParallelGroup = READER)
    fun read(): String =
        when (val result = decide()) {
            is ReadResult.Data -> {
                slot.returnHandOff()
                label(result.buffer)
            }
            ReadResult.End -> {
                slot.finishReads()
                "End"
            }
            ReadResult.Reset -> {
                slot.finishReads()
                "Reset"
            }
            null -> "wait"
        }

    protected fun take(): ReadResult.Data? = slot.takeQueued(handBack = { slot.returnHandOff() })?.let { ReadResult.Data(it) }

    protected abstract fun decide(): ReadResult?

    companion object {
        const val DRIVER = "driver"
        const val READER = "reader"
        private const val RESET_CODE = 0x10cL

        // Enough for every driver actor the scenarios below can generate.
        private const val MAX_CHUNKS = 8
    }
}

/** The sequential specification: a queue, a latch, and whether a read has returned the verdict. */
class StreamSlotSpec {
    private val queue = ArrayDeque<Int>()
    private var delivered = 0
    private var end = "Open"
    private var finished = false

    fun drainChunk(): Boolean {
        queue.addLast(delivered++)
        return true
    }

    fun drainLastChunkWithFin(): Boolean {
        queue.addLast(delivered++)
        latch("Fin")
        return true
    }

    fun drainReset(): Boolean = latch("Reset")

    fun readerSawFin() {
        latch("Fin")
    }

    fun read(): String {
        queue.removeFirstOrNull()?.let { return "chunk#$it" }
        return when (end) {
            "Open" -> "wait"
            "Fin" -> "End".also { finished = true }
            else -> "Reset".also { finished = true }
        }
    }

    private fun latch(verdict: String): Boolean {
        if (end != "Open") return false
        end = verdict
        return true
    }
}

/** The production decision, [StreamSlot.queuedOrLatchedVerdict]. */
class StreamSlotLincheckTest : StreamSlotModel() {
    override fun decide(): ReadResult? = slot.queuedOrLatchedVerdict { take() }

    @Test
    fun aReadNeverReportsTheEndOverAChunkTheDriverDelivered() {
        streamSlotOptions().check(this::class)
    }
}

/**
 * The control arm: the `Done` branch's decision as it stood before [StreamSlot.queuedOrLatchedVerdict]
 * — take from the queue, and only when that finds nothing read the latched verdict. Between those two
 * reads the driver's teardown drain can queue the stream's last chunk and latch its FIN, and this read
 * then returns End over that chunk. It MUST fail; if it ever passes, this suite can no longer see the
 * defect and [StreamSlotLincheckTest] passing means nothing.
 */
class QueueFirstVerdictLincheckTest : StreamSlotModel() {
    override fun decide(): ReadResult? =
        take() ?: when (slot.end) {
            StreamEnd.Open -> null
            StreamEnd.Fin -> ReadResult.End
            is StreamEnd.Reset -> ReadResult.Reset
        }

    @Test
    fun queueFirstDecisionReportsTheEndOverADeliveredChunk() {
        assertFailsWith<LincheckAssertionError>(
            "the queue-first read decision survived model checking. Either Lincheck's instrumentation " +
                "is inert in this module, or this arm no longer models the race it was written to convict.",
        ) {
            streamSlotOptions().check(this::class)
        }
    }
}

/**
 * The same budget for both arms, so "one fails and one passes" compares like with like. The custom
 * scenario is the #318 race itself — the drain of a stream's last chunk and FIN against a read that
 * finds the queue empty — and the generated ones add more chunks, Reset and a reader-side FIN around
 * it.
 */
private fun streamSlotOptions(): ModelCheckingOptions =
    ModelCheckingOptions()
        .sequentialSpecification(StreamSlotSpec::class.java)
        .addCustomScenario {
            parallel {
                thread { actor(StreamSlotModel::drainLastChunkWithFin) }
                thread { actor(StreamSlotModel::read) }
            }
            post { actor(StreamSlotModel::read) }
        }.threads(2)
        .actorsPerThread(3)
        .actorsBefore(1)
        .actorsAfter(1)
        .iterations(LincheckBudget.scaled(30))
        .invocationsPerIteration(LincheckBudget.scaled(500))
