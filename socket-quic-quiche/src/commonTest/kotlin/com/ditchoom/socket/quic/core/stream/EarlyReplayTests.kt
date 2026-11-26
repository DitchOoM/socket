package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.StreamSendStep
import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals

/** A replay goes in the order the sends were made, ahead of later sends on its streams, and is over once quiche took it all. */
class EarlyReplayTests {
    /** What a fake quiche took: (stream, offset into the kept bytes, length, end); it takes at most [room] bytes a send. */
    private class Quiche(
        var room: Int,
    ) {
        val took = ArrayList<String>()

        fun send(
            stream: Long,
            address: Long,
            length: Int,
            end: WriteEnd,
        ): StreamSendStep {
            if (length > 0 && room == 0) return StreamSendStep.of(StreamSendStep.QUICHE_ERR_DONE_CODE) { QuicAppErrorCode(0L) }
            val n = minOf(length, room)
            room -= n
            took += "$stream@$address+$n${if (n == length) end else WriteEnd.More}"
            return StreamSendStep.taken(n)
        }
    }

    @Test
    fun aReplayTakenWholeIsOverAtOnceAndInOrder() {
        val quiche = Quiche(room = 100)
        var done = 0
        val replay = EarlyReplay()
        replay.start(
            listOf(Replayed(0, 1000, 0, WriteEnd.More), Replayed(2, 2000, 5, WriteEnd.Finish), Replayed(0, 3000, 4, WriteEnd.Finish)),
            Runnable { done++ },
            ReplayStart.Now,
            quiche::send,
        )
        assertEquals(listOf("0@1000+0More", "0@3000+4Finish", "2@2000+5Finish"), quiche.took)
        assertEquals(1, done)
        assertEquals(false, replay.holds(0))
    }

    @Test
    fun aStreamQuicheCannotTakeWholeIsHeldUntilItCanAndItsFinAloneFollowsIt() {
        val quiche = Quiche(room = 3)
        var done = 0
        val replay = EarlyReplay()
        replay.start(listOf(Replayed(0, 1000, 5, WriteEnd.More)), Runnable { done++ }, ReplayStart.Now, quiche::send)
        assertEquals(true, replay.holds(0), "two bytes are left")
        replay.finAfter(0)
        assertEquals(Replaying.Behind, replay.resume(0, quiche::send))
        quiche.room = 10
        assertEquals(Replaying.Clear, replay.resume(0, quiche::send))
        assertEquals(listOf("0@1000+3More", "0@1003+2More", "0@0+0Finish"), quiche.took)
        assertEquals(1, done, "over once, after the FIN")
    }

    @Test
    fun aReplayHeldForTheServersCheckHandsQuicheNothingUntilReleased() {
        val quiche = Quiche(room = 100)
        var done = 0
        val replay = EarlyReplay()
        replay.start(listOf(Replayed(0, 1000, 5, WriteEnd.Finish)), Runnable { done++ }, ReplayStart.AfterCheck, quiche::send)
        assertEquals(Replaying.Behind, replay.resume(0, quiche::send), "quiche reported the stream writable before the check")
        assertEquals(emptyList(), quiche.took)
        replay.release(quiche::send)
        assertEquals(listOf("0@1000+5Finish"), quiche.took)
        assertEquals(1, done)
    }
}
