package com.ditchoom.socket

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A name stands for every address it resolves to, and a connect that stops at the first one is a
 * defect the JVM had: `getByName` returns one record, so a host whose AAAA is unreachable never fell
 * back to its A. These pin the answer's shape, the order, and that every platform tries the next
 * address when the first does not answer.
 */
class HostResolverTests {
    private val v6a = ResolvedAddress("2001:db8::1", IpFamily.V6)
    private val v6b = ResolvedAddress("2001:db8::2", IpFamily.V6)
    private val v4a = ResolvedAddress("192.0.2.1", IpFamily.V4)
    private val v4b = ResolvedAddress("192.0.2.2", IpFamily.V4)

    /** RFC 5737 TEST-NET-1: never routed, so a SYN there is dropped or refused, never answered. */
    private val unreachable = ResolvedAddress("192.0.2.1", IpFamily.V4)
    private val loopback = ResolvedAddress("127.0.0.1", IpFamily.V4)

    private fun tcpAvailable() = networkCapabilities().transports.contains(TransportKind.TCP)

    @Test
    fun familiesAreInterleavedStartingWithTheResolversFirst() {
        assertEquals(listOf(v6a, v4a, v6b, v4b), happyEyeballsOrder(listOf(v6a, v6b, v4a, v4b)))
        assertEquals(listOf(v4a, v6a, v4b, v6b), happyEyeballsOrder(listOf(v4a, v4b, v6a, v6b)))
    }

    @Test
    fun theOrderWithinAFamilyIsTheResolvers() {
        assertEquals(listOf(v6b, v4b, v6a, v4a), happyEyeballsOrder(listOf(v6b, v6a, v4b, v4a)))
        assertEquals(listOf(v4a, v4b), happyEyeballsOrder(listOf(v4a, v4b)))
        assertEquals(listOf(v6a, v4a, v4b), happyEyeballsOrder(listOf(v6a, v4a, v4b)))
    }

    @Test
    fun aResolvedAnswerIsNeverEmpty() {
        assertFailsWith<IllegalArgumentException> { Resolution.Resolved(emptyList()) }
        assertEquals(Resolution.NoAddress("example.invalid"), resolvedInOrder(emptyList(), "example.invalid"))
    }

    @Test
    fun localhostIsLoopbackWithoutAQueryIPv4First() =
        runTestNoTimeSkipping {
            val loopback = listOf(ResolvedAddress("127.0.0.1", IpFamily.V4), ResolvedAddress("::1", IpFamily.V6))
            for (name in listOf("localhost", "LocalHost.", "api.localhost")) {
                assertEquals(loopback, assertIs<Resolution.Resolved>(HostResolver.platform().resolve(name), name).candidates, name)
            }
        }

    @Test
    fun theResolverAnswersAnAddressLiteralWithItselfWithoutAskingDns() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            for ((literal, family) in listOf("127.0.0.1" to IpFamily.V4, "::1" to IpFamily.V6, "192.0.2.7" to IpFamily.V4)) {
                val resolved = assertIs<Resolution.Resolved>(HostResolver.platform().resolve(literal), literal)
                val answer = resolved.candidates.single()
                assertEquals(family, answer.family, literal)
                // The JDK writes ::1 out in full; any spelling of the same address is itself.
                assertTrue(answer.ip == literal || (literal == "::1" && answer.ip == "0:0:0:0:0:0:0:1"), "$literal answered ${answer.ip}")
            }
        }

    @Test
    fun aNameThatDoesNotExistIsAnAnswerNotAnException() =
        runTestNoTimeSkipping {
            val answer = HostResolver.platform().resolve("this.host.does.not.exist.invalid")
            assertTrue(answer is Resolution.Failed || answer is Resolution.NoAddress, "got $answer")
            if (answer is Resolution.Failed) {
                val expected =
                    if (tcpAvailable()) {
                        listOf(ResolveFailure.NotFound, ResolveFailure.TimedOut, ResolveFailure.NoNetwork)
                    } else {
                        listOf(ResolveFailure.Unsupported)
                    }
                assertTrue(answer.why in expected, "a name that does not exist is a typed failure, got ${answer.why}")
            }
        }

    /**
     * Cancelling never waits for the lookup: it costs what cancelling any suspended coroutine costs. Each round cancels
     * a lookup and, beside it, a coroutine that only waits to be cancelled, so both see the machine as it was then; a
     * cancel that waited would wait for DNS in EVERY round, far past the idle coroutine's cancel. One scheduler stall
     * hits one sample, so the medians are compared, and every sample is printed on failure. That a lookup which never
     * answers does not hold its cancelled caller at all is [BlockingHostResolverTests]' to show, with a lookup the
     * test holds.
     */
    @Test
    fun cancellingALookupCostsWhatCancellingAnIdleCoroutineDoes() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val lookups = CancelTimes()
            val idles = CancelTimes()
            repeat(ROUNDS) { round ->
                val lookup = launch(start = CoroutineStart.UNDISPATCHED) { HostResolver.platform().resolve("cancel-$round.lookup.invalid") }
                lookups.time(lookup)
                idles.time(launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() })
            }
            val detail = "sorted after warm-up, us: lookup $lookups; idle coroutine $idles"
            assertTrue(
                lookups.medianCall() <= boundMicros(idles.medianCall()),
                "median cancel() of a lookup ${lookups.medianCall()} us, bound ${boundMicros(idles.medianCall())} us; $detail",
            )
            assertTrue(
                lookups.medianJoin() <= boundMicros(idles.medianJoin()),
                "median cancel+join of a lookup ${lookups.medianJoin()} us, bound ${boundMicros(idles.medianJoin())} us; $detail",
            )
        }

    /** How long cancel() and cancel+join took, per round, in microseconds. */
    private class CancelTimes {
        private val calls = ArrayList<Long>()
        private val joins = ArrayList<Long>()

        suspend fun time(job: Job) {
            val start = TimeSource.Monotonic.markNow()
            job.cancel()
            calls += start.elapsedNow().inWholeMicroseconds
            job.join()
            joins += start.elapsedNow().inWholeMicroseconds
        }

        fun medianCall(): Long = median(calls)

        fun medianJoin(): Long = median(joins)

        private fun median(samples: List<Long>): Long = samples.drop(WARM_UP).sorted().let { it[it.size / 2] }

        override fun toString(): String = "cancel() calls=${calls.drop(WARM_UP).sorted()}; cancel+join=${joins.drop(WARM_UP).sorted()}"
    }

    /** Three times what cancelling an idle coroutine cost in the same rounds, plus half a millisecond. */
    private fun boundMicros(idleMicros: Long): Long = idleMicros * 3 + 500

    private companion object {
        const val ROUNDS = 30
        const val WARM_UP = 10
    }

    @Test
    fun aConnectFallsBackToTheNextAddressWhenTheFirstDoesNotAnswer() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val accepted = server.bind(host = "127.0.0.1")
            val serverJob =
                launch(Dispatchers.Default) {
                    accepted.serveEach { client ->
                        client.writeString(client.readString())
                    }
                }
            val config =
                TransportConfig(
                    connectTimeout = 2.seconds,
                    nameResolution = NameResolution.Via { Resolution.Resolved(listOf(unreachable, loopback)) },
                )
            val started = TimeSource.Monotonic.markNow()
            val client = ClientSocket.connect(server.port(), hostname = "echo.example", config = config)
            val took = started.elapsedNow()
            try {
                assertTrue(
                    took < 1.5.seconds,
                    "an unreachable first address costs one attempt delay (RFC 8305: 250 ms), not the 2 s connect timeout: $took",
                )
                client.writeString("fallback")
                assertEquals("fallback", client.readString())
            } finally {
                client.close()
                server.close()
                serverJob.cancel()
            }
        }

    @Test
    fun aConnectWithNoReachableAddressFailsWithTheLastAttemptsError() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val config =
                TransportConfig(
                    connectTimeout = 1.seconds,
                    nameResolution = NameResolution.Via { Resolution.Resolved(listOf(unreachable)) },
                )
            val started = TimeSource.Monotonic.markNow()
            try {
                ClientSocket.connect(9, hostname = "blackhole.example", config = config)
                throw AssertionError("192.0.2.1 must not accept a connection")
            } catch (_: TimeoutCancellationException) {
                // the JVM's own per-attempt deadline
            } catch (_: SocketException) {
                // every other backend's typed deadline, or an ICMP unreachable from the first hop
            }
            assertTrue(started.elapsedNow() < 10.seconds, "the failure is the attempt's own bound: ${started.elapsedNow()}")
        }

    @Test
    fun aResolverThatAnswersNoAddressIsAnUnknownHost() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val config = TransportConfig(nameResolution = NameResolution.Via { Resolution.NoAddress(it) })
            assertFailsWith<SocketUnknownHostException> { ClientSocket.connect(9, hostname = "nowhere.example", config = config) }
        }
}
