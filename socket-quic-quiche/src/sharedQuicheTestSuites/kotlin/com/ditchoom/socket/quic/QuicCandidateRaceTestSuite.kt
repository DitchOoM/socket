package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectPacing
import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.NameResolution
import com.ditchoom.socket.Resolution
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Racing QUIC handshakes across a peer's candidate endpoints, against this target's real quiche and
 * real UDP sockets.
 *
 * `QuicConnectRaceTests` pins the racer's semantics deterministically over a scripted engine; nothing
 * there touches a socket. This is the other half: that the endpoint and the server name really are two
 * arguments all the way down to `quiche_connect` on **this** backend, that an address which does not
 * answer costs one attempt delay rather than the connect budget, and that a candidate which loses
 * leaves no connection behind — measured at the peer, which is the only place a connection that was
 * not torn down would still be visible.
 *
 * Addresses come from the documentation ranges (RFC 5737 `192.0.2.0/24`, RFC 3849 `2001:db8::/32`)
 * precisely because nothing routes them: an attempt to one is either dropped or refused, and both read
 * as "this candidate is not the connection".
 */
abstract class QuicCandidateRaceTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options = QuicOptions(alpnProtocols = listOf("race-test"), verifyPeer = false)

    private fun v4(ip: String) = ResolvedAddress(ip, IpFamily.V4)

    @Test
    fun anAddressThatNeverAnswersCostsOneAttemptDelayRatherThanTheConnectBudget() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { connections { awaitCancellation() } }
                    try {
                        // The name is never looked up: this resolver is what turns it into candidates, and
                        // the first of them is a blackhole. Before a QUIC connect consulted the resolver at
                        // all, this name had no address and the connect could not even start.
                        val resolver =
                            HostResolver { host ->
                                Resolution
                                    .Resolved(listOf(v4("192.0.2.1"), v4("127.0.0.1")))
                                    .also { assertEquals("candidate-race.invalid", host) }
                            }
                        val config =
                            TransportConfig(
                                nameResolution = NameResolution.Via(resolver),
                                connectPacing = ConnectPacing.Staggered.RECOMMENDED,
                            )
                        val started = TimeSource.Monotonic.markNow()
                        withQuicConnection(
                            QuicPeer.Named("candidate-race.invalid", port),
                            options,
                            config,
                            timeout = 30.seconds,
                        ) { race ->
                            val elapsed = started.elapsedNow()
                            assertIs<QuicCandidateRace.Raced>(race, "two candidates were offered, so this was a race")
                            assertEquals(
                                QuicEndpoint(v4("127.0.0.1"), port),
                                race.winner,
                                "the reachable candidate is the connection; lost: ${race.lost}",
                            )
                            assertTrue(
                                elapsed < 15.seconds,
                                "a candidate that never answers must cost one attempt delay, not the whole " +
                                    "30s connect budget — the connect took $elapsed (race: $race)",
                            )
                            assertEquals("race-test", negotiatedAlpn, "the winner is a usable, established connection")
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun aCandidateListSpansBothFamiliesAndADeadOneCostsOneAttempt() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { connections { awaitCancellation() } }
                    try {
                        val dead = QuicEndpoint(ResolvedAddress("2001:db8::1", IpFamily.V6), port)
                        val live = QuicEndpoint(v4("127.0.0.1"), port)
                        val started = TimeSource.Monotonic.markNow()
                        withQuicConnection(
                            QuicPeer.Candidates(listOf(dead, live), serverName = "localhost"),
                            options,
                            TransportConfig(),
                            timeout = 30.seconds,
                        ) { race ->
                            val elapsed = started.elapsedNow()
                            assertIs<QuicCandidateRace.Raced>(race)
                            assertEquals(live, race.winner, "the v6 documentation address routes nowhere; lost: ${race.lost}")
                            assertEquals(listOf(dead), race.lost.map { it.endpoint })
                            assertTrue(
                                elapsed < 15.seconds,
                                "a dead first family must cost one attempt, not the connect budget — took $elapsed",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun aSimultaneousRaceAgainstTwoLivePeersKeepsOneAndClosesTheOther() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                // Two real servers in this process, so BOTH candidates answer — the only shape in which a
                // race really does produce a second established connection that has to be closed.
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val first = this
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                        val second = this
                        val jobs =
                            listOf(
                                launch { first.connections { awaitCancellation() } },
                                launch { second.connections { awaitCancellation() } },
                            )
                        try {
                            val candidates =
                                listOf(
                                    QuicEndpoint(v4("127.0.0.1"), first.port),
                                    QuicEndpoint(v4("127.0.0.1"), second.port),
                                )
                            withQuicConnection(
                                QuicPeer.Candidates(candidates, serverName = "localhost"),
                                options,
                                TransportConfig(connectPacing = ConnectPacing.Simultaneous),
                                timeout = 30.seconds,
                            ) { race ->
                                assertIs<QuicCandidateRace.Raced>(race)
                                assertTrue(race.winner in candidates, "the winner is one of the two peers: ${race.winner}")
                                assertEquals(1, race.lost.size, "one candidate lost; lost: ${race.lost}")
                                val loss = race.lost.single()
                                assertTrue(
                                    loss is QuicCandidateLoss.ClosedAsLate || loss is QuicCandidateLoss.Abandoned,
                                    "both candidates were live, so the loser either finished and was closed or was " +
                                        "abandoned mid-handshake — never left as a second connection nobody owns: $loss",
                                )
                                assertTrue(race.winner != loss.endpoint, "the winner cannot also be the loser")
                                assertEquals("race-test", negotiatedAlpn, "the surviving connection is usable")
                            }
                        } finally {
                            jobs.forEach { it.cancel() }
                        }
                    }
                }
            }
        }
}
