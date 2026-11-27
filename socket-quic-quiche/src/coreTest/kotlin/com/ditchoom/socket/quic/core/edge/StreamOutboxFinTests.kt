package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.stream.WriteEnd
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a stream's send queue puts our FIN. The sender publishes its last bytes in two steps, the FIN asked for and
 * then the tail, and the loop reads the tail and then the state, so the loop can see the FIN without the bytes queued
 * with it. These pin each step the loop can take from such a view.
 */
class StreamOutboxFinTests {
    @Test
    fun aFinAskedAfterTheLoopReadTheTailWaitsForTheBytesQueuedWithIt() {
        // The loop read the tail at STALE_TAIL; the sender then queued LAST more bytes and asked for our FIN after them.
        val asked = OutboxState.FinishAsked(STALE_TAIL + LAST)
        assertEquals(WriteEnd.More, stepEnd(asked, SENT, STALE_TAIL, (STALE_TAIL - SENT).toInt()), "the bytes the loop saw")
        assertEquals(WriteEnd.More, stepEnd(asked, STALE_TAIL, STALE_TAIL, 0), "nothing the loop saw is left")
    }

    @Test
    fun theStepWhoseBytesReachTheFinCarriesIt() {
        val asked = OutboxState.FinishAsked(STALE_TAIL + LAST)
        assertEquals(WriteEnd.Finish, stepEnd(asked, STALE_TAIL, STALE_TAIL + LAST, LAST))
        assertEquals(WriteEnd.Finish, stepEnd(asked, STALE_TAIL + LAST, STALE_TAIL + LAST, 0))
    }

    @Test
    fun aStepThatEndsShortOfTheFinDoesNotCarryIt() {
        val asked = OutboxState.FinishAsked(STALE_TAIL + LAST)
        // The ring's end cut the step short of the bytes the loop saw.
        assertEquals(WriteEnd.More, stepEnd(asked, STALE_TAIL, STALE_TAIL + LAST, LAST - 1))
    }

    @Test
    fun anOpenQueueNeverCarriesAFin() {
        assertEquals(WriteEnd.More, stepEnd(OutboxState.Open, SENT, STALE_TAIL, (STALE_TAIL - SENT).toInt()))
    }

    private companion object {
        const val SENT = 1_000_000L
        const val STALE_TAIL = 1_012_375L
        const val LAST = 36_201
    }
}
