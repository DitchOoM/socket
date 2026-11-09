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
 * The driver handing a parked read its bytes ([StreamSlot.takeWaitingRead], `QuicheDriver.handToWaitingRead`)
 * against the read moving on, model checked for the one property that matters: the read gets the stream's
 * bytes in order.
 *
 * ## The model
 *
 * quiche is an endless stream of numbered chunks. The **driver loop** is serial, modelled as one lock that
 * every loop action holds: the hand-off (take the read's advert; if there was one, read the next chunk and
 * queue it on the slot) and the read's own `StreamRecv`. The **reader** advertises a parked read and
 * withdraws it, as [StreamSlot.awaitData] does around its wait, then takes what was queued, else sends a
 * `StreamRecv`. Each read reports whether the chunk it got is the next one in stream order.
 *
 * The race: the driver takes the advert and, before its chunk is on the queue, the read withdraws, finds
 * the queue empty and sends a `StreamRecv`. That `StreamRecv` runs after the hand-off (the loop is
 * serial), when quiche's next chunk is the one AFTER the queued one. The driver answers it `Done` while
 * the queue holds anything, so the read takes the queued chunk first. [UnguardedHandOffLincheckTest] is
 * the same model without that answer, and it MUST fail.
 */
abstract class HandOffModel(
    private val doneWhileQueued: Boolean,
) {
    private val slot = StreamSlot(QuicStreamId(0))
    private val loop = Any()
    private val chunks: List<ReadBuffer> = List(MAX_CHUNKS) { BufferFactory.Default.allocate(1) }
    private var quicheNext = 0 // guarded by loop
    private var expected = 0 // reader only

    private fun index(chunk: ReadBuffer): Int = chunks.indexOfFirst { it === chunk }

    @Operation(nonParallelGroup = DRIVER)
    fun streamBecameReadable() {
        synchronized(loop) {
            if (slot.takeWaitingRead() > 0) slot.deliverDrained(chunks[quicheNext++], fin = false)
        }
    }

    @Operation(nonParallelGroup = READER)
    fun parkThenRead(): String {
        slot.advertiseWaitingRead(1)
        slot.withdrawWaitingRead()
        val got =
            take() ?: synchronized(loop) {
                if (doneWhileQueued && !slot.pendingData.isEmpty) null else chunks[quicheNext++]
            } ?: take()
        val i = index(checkNotNull(got) { "Done with nothing queued" })
        return if (i == expected++) "in order" else "chunk#$i before chunk#${expected - 1}"
    }

    private fun take(): ReadBuffer? =
        (
            slot.queuedOrLatchedVerdict {
                slot.takeQueued(handBack = { slot.returnHandOff() })?.let { ReadResult.Data(it) }
            } as? ReadResult.Data
        )?.buffer

    companion object {
        const val DRIVER = "driver"
        const val READER = "reader"
        private const val MAX_CHUNKS = 16
    }
}

/** Every read gets the next chunk in stream order. */
class HandOffSpec {
    fun streamBecameReadable() = Unit

    fun parkThenRead(): String = "in order"
}

class StreamSlotHandOffLincheckTest : HandOffModel(doneWhileQueued = true) {
    @Test
    fun aParkedReadGetsTheStreamInOrder() {
        handOffOptions().check(this::class)
    }
}

/** The control arm: no `Done` while the queue holds a chunk. It MUST fail. */
class UnguardedHandOffLincheckTest : HandOffModel(doneWhileQueued = false) {
    @Test
    fun withoutTheGuardAReadOvertakesTheQueuedChunk() {
        assertFailsWith<LincheckAssertionError>(
            "the unguarded hand-off survived model checking: either Lincheck's instrumentation is inert here, " +
                "or this arm no longer models the race it was written to convict.",
        ) {
            handOffOptions().check(this::class)
        }
    }
}

private fun handOffOptions(): ModelCheckingOptions =
    ModelCheckingOptions()
        .sequentialSpecification(HandOffSpec::class.java)
        .addCustomScenario {
            parallel {
                thread { actor(HandOffModel::streamBecameReadable) }
                thread { actor(HandOffModel::parkThenRead) }
            }
        }.threads(2)
        .actorsPerThread(3)
        .actorsBefore(0)
