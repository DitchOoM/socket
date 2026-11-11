package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The driver handing a parked read the stream's END (`QuicheDriver.handToWaitingRead` meeting a bare FIN
 * or a RESET) against that read's own `StreamRecv`, model checked for the property that matters: the read
 * reports the end, never an error from quiche.
 *
 * ## The model
 *
 * quiche holds a stream whose only content is its end; consuming the end collects the stream, and any
 * later `StreamRecv` on it is an error. The **driver loop** is serial, modelled as one lock held by every
 * loop action: the hand-off (take the read's advert; if there was one, consume the end from quiche and
 * latch it on the slot) and the read's `StreamRecv`, answered by [StreamSlot.answersStreamRecvWithDone],
 * the decision the driver itself makes. The **reader** advertises and withdraws a parked read as
 * [StreamSlot.awaitData] does, takes the latched verdict, else sends a `StreamRecv`.
 *
 * The race: the driver takes the advert, the read withdraws and finds nothing latched, and the hand-off
 * consumes the end before the read's `StreamRecv` runs. [UnguardedHandOffEndLincheckTest] answers by
 * the queue alone, and it MUST fail.
 */
abstract class HandOffEndModel(
    private val end: ModelEnd,
    private val answersDone: (StreamSlot) -> Boolean,
) {
    enum class ModelEnd { Fin, Reset }

    private enum class Quiche { Holding, Collected }

    private val slot = StreamSlot(QuicStreamId(0))
    private val loop = Any()
    private var quiche = Quiche.Holding // guarded by loop

    /** Consumes quiche's end and latches it on the slot; false when the stream was already collected. */
    private fun consumeEnd(): Boolean {
        if (quiche == Quiche.Collected) return false
        quiche = Quiche.Collected
        when (end) {
            ModelEnd.Fin -> slot.latchFin()
            ModelEnd.Reset -> slot.latchReset(QuicAppErrorCode(0))
        }
        return true
    }

    @Operation(nonParallelGroup = DRIVER)
    fun streamBecameReadable() {
        synchronized(loop) {
            if (slot.takeWaitingRead() > 0) consumeEnd()
        }
    }

    @Operation(nonParallelGroup = READER)
    fun parkThenRead(): String {
        slot.advertiseWaitingRead(1)
        slot.withdrawWaitingRead()
        return verdict() ?: synchronized(loop) {
            if (answersDone(slot)) {
                null
            } else if (consumeEnd()) {
                "end"
            } else {
                "InvalidStreamState"
            }
        } ?: verdict() ?: "Done with nothing latched"
    }

    private fun verdict(): String? =
        when (slot.queuedOrLatchedVerdict { null }) {
            ReadResult.End, ReadResult.Reset -> "end"
            else -> null
        }

    companion object {
        const val DRIVER = "driver"
        const val READER = "reader"
    }
}

/** The read reports the stream's end. */
class HandOffEndSpec {
    fun streamBecameReadable() = Unit

    fun parkThenRead(): String = "end"
}

class StreamSlotHandOffFinLincheckTest : HandOffEndModel(ModelEnd.Fin, StreamSlot::answersStreamRecvWithDone) {
    @Test
    fun aParkedReadReportsABareFin() {
        handOffEndOptions().check(this::class)
    }
}

class StreamSlotHandOffResetLincheckTest : HandOffEndModel(ModelEnd.Reset, StreamSlot::answersStreamRecvWithDone) {
    @Test
    fun aParkedReadReportsAReset() {
        handOffEndOptions().check(this::class)
    }
}

/** The control arm: `Done` only while the queue holds a chunk. It MUST fail. */
class UnguardedHandOffEndLincheckTest : HandOffEndModel(ModelEnd.Fin, { !it.pendingData.isEmpty }) {
    @Test
    fun withoutTheEndAnswerAReadAsksQuicheAboutACollectedStream() {
        assertFailsWith<LincheckAssertionError>(
            "the unguarded end hand-off survived model checking: either Lincheck's instrumentation is inert " +
                "here, or this arm no longer models the race it was written to convict.",
        ) {
            handOffEndOptions().check(this::class)
        }
    }
}

private fun handOffEndOptions(): ModelCheckingOptions =
    ModelCheckingOptions()
        .sequentialSpecification(HandOffEndSpec::class.java)
        .addCustomScenario {
            parallel {
                thread { actor(HandOffEndModel::streamBecameReadable) }
                thread { actor(HandOffEndModel::parkThenRead) }
            }
        }.threads(2)
        .actorsPerThread(1)
        .actorsBefore(0)
        .iterations(LincheckBudget.scaled(10))
        .invocationsPerIteration(LincheckBudget.scaled(1_000))
