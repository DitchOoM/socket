package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [LastOutHandoff] state machine, transition by transition, and its one invariant under
 * contention: however users and the closer interleave, exactly one party is told it is last out.
 */
class LastOutHandoffTests {
    @Test
    fun closeWithNobodyInside_isTheClosersToDo_andASecondCloseIsNot() {
        val handoff = LastOutHandoff()
        assertFalse(handoff.closed)
        assertEquals(LastOutHandoff.Closing.LastOut, handoff.close())
        assertTrue(handoff.closed)
        assertEquals(LastOutHandoff.Closing.AlreadyClosed, handoff.close())
    }

    @Test
    fun closeWithAUserInside_handsOff_andThatUserIsLastOut() {
        val handoff = LastOutHandoff()
        assertEquals(LastOutHandoff.Admission.Admitted, handoff.enter())
        assertEquals(LastOutHandoff.Closing.HandedOff, handoff.close())
        assertTrue(handoff.closed, "closed is visible while the user is still inside")
        assertEquals(LastOutHandoff.Departure.LastOut, handoff.exit())
    }

    @Test
    fun enterAfterClose_isRefused_andOwesNoExit() {
        val handoff = LastOutHandoff()
        assertEquals(LastOutHandoff.Closing.LastOut, handoff.close())
        assertEquals(LastOutHandoff.Admission.Refused, handoff.enter())
        // Refused did not count anyone in, so the word is still closed-and-empty: an exit here would
        // be the programming error below, not a second LastOut.
        assertFailsWith<IllegalStateException> { handoff.exit() }
    }

    @Test
    fun exitBeforeClose_isNotLast_andTheLaterCloseIsLastOut() {
        val handoff = LastOutHandoff()
        assertEquals(LastOutHandoff.Admission.Admitted, handoff.enter())
        assertEquals(LastOutHandoff.Departure.NotLast, handoff.exit())
        assertEquals(LastOutHandoff.Closing.LastOut, handoff.close())
    }

    @Test
    fun withTwoUsersInside_onlyTheSecondToLeaveIsLastOut() {
        val handoff = LastOutHandoff()
        assertEquals(LastOutHandoff.Admission.Admitted, handoff.enter())
        assertEquals(LastOutHandoff.Admission.Admitted, handoff.enter())
        assertEquals(LastOutHandoff.Closing.HandedOff, handoff.close())
        assertEquals(LastOutHandoff.Departure.NotLast, handoff.exit())
        assertEquals(LastOutHandoff.Departure.LastOut, handoff.exit())
    }

    @Test
    fun exitWithoutEnter_isAProgrammingError() {
        assertFailsWith<IllegalStateException> { LastOutHandoff().exit() }
    }

    @Test
    fun underContention_exactlyOnePartyIsLastOut() =
        runBlocking {
            repeat(ROUNDS) {
                val handoff = LastOutHandoff()
                val lastOuts = AtomicInt(0)
                val users =
                    List(USERS) {
                        async(Dispatchers.Default) {
                            var refusedAt = -1
                            for (i in 0 until ENTRIES_PER_USER) {
                                when (handoff.enter()) {
                                    LastOutHandoff.Admission.Refused -> {
                                        refusedAt = i
                                        break
                                    }
                                    LastOutHandoff.Admission.Admitted -> Unit
                                }
                                when (handoff.exit()) {
                                    LastOutHandoff.Departure.NotLast -> Unit
                                    LastOutHandoff.Departure.LastOut -> lastOuts.incrementAndGet()
                                }
                            }
                            refusedAt
                        }
                    }
                val closer =
                    async(Dispatchers.Default) {
                        when (handoff.close()) {
                            LastOutHandoff.Closing.LastOut -> lastOuts.incrementAndGet()
                            LastOutHandoff.Closing.HandedOff -> Unit
                            LastOutHandoff.Closing.AlreadyClosed -> error("only one closer in this round")
                        }
                    }
                closer.await()
                val refusals = users.awaitAll()
                assertEquals(1, lastOuts.value, "round $it: refusals at $refusals")
                assertTrue(handoff.closed)
            }
        }

    private companion object {
        const val ROUNDS = 200
        const val USERS = 8
        const val ENTRIES_PER_USER = 1_000
    }
}
