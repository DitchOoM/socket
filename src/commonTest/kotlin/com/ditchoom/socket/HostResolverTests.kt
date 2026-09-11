package com.ditchoom.socket

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
    fun thePlatformResolverAnswersLocalhostWithALoopbackLiteral() =
        runTestNoTimeSkipping {
            val answer = HostResolver.platform().resolve("localhost")
            if (!tcpAvailable()) {
                assertIs<Resolution.Failed>(answer, "a browser has no resolver, and says so as an answer")
                return@runTestNoTimeSkipping
            }
            val resolved = assertIs<Resolution.Resolved>(answer)
            assertTrue(
                resolved.candidates.any { it.ip == "127.0.0.1" || it.ip == "::1" },
                "localhost resolves to a loopback literal: ${resolved.candidates}",
            )
        }

    @Test
    fun aNameThatDoesNotExistIsAnAnswerNotAnException() =
        runTestNoTimeSkipping {
            val answer = HostResolver.platform().resolve("this.host.does.not.exist.invalid")
            assertTrue(answer is Resolution.Failed || answer is Resolution.NoAddress, "got $answer")
        }

    @Test
    fun aConnectFallsBackToTheNextAddressWhenTheFirstDoesNotAnswer() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val accepted = server.bind(host = "127.0.0.1")
            val serverJob =
                launch(Dispatchers.Default) {
                    accepted.collect { client ->
                        client.writeString(client.readString())
                        client.close()
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
                assertTrue(took < 10.seconds, "one unreachable address costs one attempt, not the run: $took")
                client.writeString("fallback")
                assertEquals("fallback", client.readString())
            } finally {
                client.close()
                serverJob.cancel()
                server.close()
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
