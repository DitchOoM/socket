package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A wait cancelled with the signal that woke it in flight leaves the stream [ReaderMay.Ask], so the next read asks.
 *
 * The loop's signal resumes the parked receiver, but the resumption runs later on the reader's dispatcher;
 * a cancellation landing in between wins, and the channel does not hand the element to anyone else. The
 * bytes it announced stay in quiche with no wake left, so only the stream's state can tell the next read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSlotWaitCancelMarkTest {
    @Test
    fun aWaitCancelledWithItsSignalInFlightMakesTheNextReadAsk() =
        runTest {
            val slot = StreamSlot(QuicStreamId(0))
            val reader = launch(StandardTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) { slot.awaitData(16) }
            // Parked in awaitData. The loop signals; the resumption is queued but has not run.
            slot.dataSignal.trySend(Unit)
            reader.cancel()
            runCurrent()
            assertTrue(reader.isCancelled)
            assertTrue(slot.readNeedsQuicheAnswer(), "the signal died with the cancelled wait and the stream was not left asking")
        }

    @Test
    fun aClosedSignalIsNotMarkedAsACancelledWait() =
        runTest {
            val slot = StreamSlot(QuicStreamId(0))
            slot.dataSignal.close()
            runCatching { slot.awaitData(16) }
            assertFalse(slot.readNeedsQuicheAnswer(), "a closed channel is the connection's end, not a swallowed signal")
        }
}
