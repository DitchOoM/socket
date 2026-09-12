package com.ditchoom.socket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * RFC 8305 §5 under virtual time: every case asserts *when* attempts start and which of them end
 * up open, not merely that something connected. The scripted attempt answers each candidate the
 * way a real address would — silence, a fast refusal, a connect after one round trip, or a failure
 * about the peer itself.
 */
class ConnectRaceTests {
    private sealed interface Address {
        /** Never answers; a SYN into a blackhole. */
        data object Silent : Address

        /** Refuses after [after]. */
        data class Refuses(
            val after: Duration,
        ) : Address

        /** Connects after [after]. */
        data class Connects(
            val after: Duration,
        ) : Address

        /** Fails in a way no other address can fix. */
        data class Fatal(
            val after: Duration,
        ) : Address
    }

    private class Refused(
        val address: Address,
    ) : RuntimeException("refused by $address")

    private class PeerRejected : RuntimeException("the peer rejected us")

    private class Connection(
        val address: Address,
    ) {
        var closed = false
    }

    /** What the race did, in virtual milliseconds. */
    private class Script(
        private val scope: TestScope,
    ) {
        val startedAt = ArrayList<Long>()
        val closed = ArrayList<Address>()

        suspend fun attempt(address: Address): Connection {
            startedAt += scope.testScheduler.currentTime
            when (address) {
                Address.Silent -> delay(1.seconds * 3600)
                is Address.Refuses -> {
                    delay(address.after)
                    throw Refused(address)
                }
                is Address.Connects -> delay(address.after)
                is Address.Fatal -> {
                    delay(address.after)
                    throw PeerRejected()
                }
            }
            return Connection(address)
        }

        suspend fun close(connection: Connection) {
            connection.closed = true
            closed += connection.address
        }

        fun verdict(error: Throwable): AttemptVerdict = if (error is PeerRejected) AttemptVerdict.Fatal else AttemptVerdict.TryOthers
    }

    private suspend fun TestScope.race(
        vararg addresses: Address,
        pacing: ConnectPacing = ConnectPacing.Staggered(250.milliseconds),
        script: Script = Script(this),
    ): Pair<Connection, Script> = connectRace(addresses.toList(), pacing, script::verdict, script::close, script::attempt) to script

    @Test
    fun theNextAttemptStartsOneDelayAfterASilentFirst() =
        runTest {
            val (connection, script) = race(Address.Silent, Address.Connects(20.milliseconds))

            assertEquals(listOf(0L, 250L), script.startedAt)
            assertEquals(270L, testScheduler.currentTime, "one attempt delay plus one round trip, not one connect timeout")
            assertEquals(Address.Connects(20.milliseconds), connection.address)
            assertEquals(emptyList(), script.closed, "the silent attempt was cancelled, so there was nothing to close")
        }

    @Test
    fun aFastRefusalStartsTheNextAttemptAtOnce() =
        runTest {
            val (_, script) = race(Address.Refuses(30.milliseconds), Address.Connects(10.milliseconds))

            assertEquals(listOf(0L, 30L), script.startedAt)
            assertEquals(40L, testScheduler.currentTime)
        }

    @Test
    fun aSuccessStopsThePacing() =
        runTest {
            val (connection, script) = race(Address.Connects(100.milliseconds), Address.Connects(1.milliseconds))

            assertEquals(listOf(0L), script.startedAt, "the second address is never tried")
            assertEquals(Address.Connects(100.milliseconds), connection.address)
        }

    @Test
    fun aConnectionThatCompletesAfterLosingIsClosedNotLeaked() =
        runTest {
            // Both complete at t=300: the first was started at 0 and takes 300, the second started at
            // 250 and takes 50. Exactly one wins; the other is closed.
            val (connection, script) = race(Address.Connects(300.milliseconds), Address.Connects(50.milliseconds))

            assertEquals(listOf(0L, 250L), script.startedAt)
            val loser =
                if (connection.address ==
                    Address.Connects(300.milliseconds)
                ) {
                    Address.Connects(50.milliseconds)
                } else {
                    Address.Connects(300.milliseconds)
                }
            assertEquals(listOf(loser), script.closed)
            assertEquals(false, connection.closed, "the winner stays open")
        }

    @Test
    fun whenEveryAttemptFailsTheLastFailureIsTheError() =
        runTest {
            val script = Script(this)
            val error =
                assertFailsWith<Refused> {
                    race(
                        Address.Refuses(400.milliseconds),
                        Address.Refuses(10.milliseconds),
                        Address.Refuses(500.milliseconds),
                        script = script,
                    )
                }

            assertEquals(listOf(0L, 250L, 260L), script.startedAt, "the second's refusal at 260 starts the third at once")
            assertEquals(Address.Refuses(500.milliseconds), error.address, "the third fails last, at 760")
            assertEquals(760L, testScheduler.currentTime)
        }

    @Test
    fun aFatalFailureEndsTheRaceBeforeTheNextAttemptStarts() =
        runTest {
            val script = Script(this)
            assertFailsWith<PeerRejected> { race(Address.Fatal(50.milliseconds), Address.Connects(1.milliseconds), script = script) }

            assertEquals(listOf(0L), script.startedAt)
            assertEquals(50L, testScheduler.currentTime)
        }

    @Test
    fun aFatalFailureOnALaterAttemptCancelsTheEarlierOne() =
        runTest {
            val script = Script(this)
            assertFailsWith<PeerRejected> { race(Address.Silent, Address.Fatal(10.milliseconds), script = script) }

            assertEquals(listOf(0L, 250L), script.startedAt)
            assertEquals(260L, testScheduler.currentTime, "the silent attempt did not hold the race open")
        }

    @Test
    fun aConnectionThatCompletesAfterAFatalFailureIsClosed() =
        runTest {
            val script = Script(this)
            assertFailsWith<PeerRejected> { race(Address.Connects(300.milliseconds), Address.Fatal(50.milliseconds), script = script) }

            assertEquals(300L, testScheduler.currentTime)
            assertEquals(listOf(Address.Connects(300.milliseconds)), script.closed)
        }

    @Test
    fun sequentialPacingWaitsForAFailureBeforeTheNext() =
        runTest {
            val (connection, script) =
                race(
                    Address.Refuses(400.milliseconds),
                    Address.Connects(10.milliseconds),
                    pacing = ConnectPacing.Sequential,
                )

            assertEquals(listOf(0L, 400L), script.startedAt)
            assertEquals(410L, testScheduler.currentTime)
            assertEquals(Address.Connects(10.milliseconds), connection.address)
        }

    @Test
    fun sequentialPacingStopsAtAFatalFailure() =
        runTest {
            val script = Script(this)
            assertFailsWith<PeerRejected> {
                race(Address.Fatal(5.milliseconds), Address.Connects(1.milliseconds), pacing = ConnectPacing.Sequential, script = script)
            }

            assertEquals(listOf(0L), script.startedAt)
        }

    @Test
    fun cancellingTheConnectCancelsEveryAttempt() =
        runTest {
            val script = Script(this)
            val connect = async { race(Address.Silent, Address.Silent, script = script) }
            advanceTimeBy(300.milliseconds)
            connect.cancel()

            assertFailsWith<CancellationException> { connect.await() }
            assertEquals(listOf(0L, 250L), script.startedAt)
            assertEquals(emptyList(), script.closed)
        }

    @Test
    fun anAttemptDelayMustBePositive() {
        assertFailsWith<IllegalArgumentException> { ConnectPacing.Staggered(Duration.ZERO) }
        assertSame(ConnectPacing.Staggered.RECOMMENDED, ConnectPacing.Staggered.RECOMMENDED)
    }
}
