package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectPacing
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Racing QUIC handshakes across a peer's candidate endpoints, under virtual time.
 *
 * Every case asserts *when* each candidate's first flight left and what became of the ones that lost,
 * not merely that something connected — and every case ends by asserting that the only connection
 * still open is the one that won. A connection here is counted when the engine mints it and again when
 * something closes it, so "nothing leaked" is a balance over the engine's own bookkeeping rather than
 * a record that someone called `close`.
 *
 * Latencies are deliberately those of a real path: at RTT≈0 a race is decided before the pacer has
 * anything to pace and every scenario here would pass against a sequential connect.
 */
class QuicConnectRaceTests {
    /** How one endpoint answers a first flight. */
    private sealed interface Answer {
        /** A blackhole: the Initials go nowhere and nothing ever comes back. */
        data object Silent : Answer

        /** The handshake fails after [after] — a refusal, an unreachable network, a version mismatch. */
        data class Fails(
            val after: Duration,
        ) : Answer

        /** The handshake completes after [after]. */
        data class Completes(
            val after: Duration,
        ) : Answer

        /**
         * The handshake completes after [after] **even once the attempt is cancelled**, the way a
         * backend that has already reached `Established` finishes what it started. This is the only
         * candidate shape that can leave a second live connection to the peer.
         */
        data class CompletesRegardless(
            val after: Duration,
        ) : Answer

        /** The peer's certificate is rejected after [after]: the same certificate is at every endpoint. */
        data class RejectsTheCertificate(
            val after: Duration,
        ) : Answer

        /**
         * The connect is refused for the caller's own arguments, before a socket is opened — every
         * attempt is built from the same [QuicOptions], so every endpoint refuses it identically.
         */
        data object RefusesTheArguments : Answer
    }

    private fun v6(
        n: Int,
        port: Int = 443,
    ) = QuicEndpoint(ResolvedAddress("2001:db8::$n", IpFamily.V6), port)

    private fun v4(
        n: Int,
        port: Int = 443,
    ) = QuicEndpoint(ResolvedAddress("192.0.2.$n", IpFamily.V4), port)

    /** One connection the scripted engine minted, so the script can tell it was closed. */
    private class Dialled(
        val endpoint: QuicEndpoint,
        private val delegate: MockQuicConnection = MockQuicConnection(),
    ) : QuicConnection by delegate {
        var closeCalls = 0
            private set

        override suspend fun close(error: QuicError) {
            closeCalls++
            delegate.close(error)
        }
    }

    /**
     * A [QuicEngine] whose single-endpoint connect answers from [answers], recording when each first
     * flight left, which identity it was dialled for, and every connection it minted.
     */
    private class ScriptedEngine(
        private val scope: TestScope,
        private val answers: Map<QuicEndpoint, Answer>,
    ) : QuicEngine {
        override val capabilities =
            EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = false)

        val startedAt = ArrayList<Long>()
        val dialled = ArrayList<QuicEndpoint>()
        val identities = ArrayList<String>()
        val minted = ArrayList<Dialled>()

        /** Connections the engine minted that nothing has closed — zero except the winner, always. */
        fun open(): List<QuicEndpoint> = minted.filter { it.closeCalls == 0 }.map { it.endpoint }

        override suspend fun connect(
            binding: QuicClientBinding,
            endpoint: QuicEndpoint,
            serverName: String,
            quicOptions: QuicOptions,
            transport: TransportConfig,
            timeout: Duration,
        ): QuicConnection {
            startedAt += scope.testScheduler.currentTime
            dialled += endpoint
            identities += serverName
            when (val answer = answers.getValue(endpoint)) {
                Answer.Silent -> delay(1.seconds * 3600)
                is Answer.Fails -> {
                    delay(answer.after)
                    throw QuicCloseException(
                        QuicCloseReason.ByLocal(QuicError.ConnectionRefused),
                        "no QUIC endpoint at $endpoint",
                    )
                }
                is Answer.Completes -> delay(answer.after)
                is Answer.CompletesRegardless -> withContext(NonCancellable) { delay(answer.after) }
                Answer.RefusesTheArguments ->
                    throw EarlyDataProtocolNotOfferedException("resume-proto", quicOptions.alpnProtocols)
                is Answer.RejectsTheCertificate -> {
                    delay(answer.after)
                    // unknown_ca (48), exactly as quiche reports a TLS alert through a CRYPTO_ERROR.
                    throw QuicCloseException(
                        QuicCloseReason.ByLocal(QuicError.CryptoError(48)),
                        "the peer's certificate is not trusted",
                    )
                }
            }
            return Dialled(endpoint).also { minted += it }
        }

        override suspend fun bind(
            binding: QuicPortBinding,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: Duration,
        ): QuicServer = throw UnsupportedOperationException("the racer never binds")
    }

    private val options = QuicOptions(alpnProtocols = listOf("race"))

    private suspend fun TestScope.race(
        vararg script: Pair<QuicEndpoint, Answer>,
        pacing: ConnectPacing = ConnectPacing.Staggered.RECOMMENDED,
        serverName: String = "peer.example",
    ): Pair<QuicRacedConnect, ScriptedEngine> {
        val engine = ScriptedEngine(this, script.toMap())
        val peer = QuicPeer.Candidates(script.map { it.first }, serverName)
        return engine.connectRacing(
            QuicClientBinding.OwnSocket,
            peer,
            options,
            TransportConfig(connectPacing = pacing),
            30.seconds,
        ) to engine
    }

    private fun assertNothingLeaked(
        engine: ScriptedEngine,
        winner: QuicRacedConnect,
    ) {
        val expected = listOf((winner.candidateRace as QuicCandidateRace.Raced).winner)
        assertEquals(
            expected,
            engine.open(),
            "every connection the engine minted except the winner must have been closed; " +
                "minted=${engine.minted.map { it.endpoint }} stillOpen=${engine.open()}",
        )
    }

    @Test
    fun aBlackholedFirstCandidateCostsOneAttemptDelayNotAConnectTimeout() =
        runTest {
            val dark = v6(1)
            val live = v4(2)
            val (connection, engine) = race(dark to Answer.Silent, live to Answer.Completes(120.milliseconds))

            assertEquals(listOf(0L, 250L), engine.startedAt, "the second candidate waits exactly one attempt delay")
            assertEquals(
                370L,
                testScheduler.currentTime,
                "one attempt delay plus one handshake, not the connect timeout: a dark first candidate " +
                    "must not cost the whole budget",
            )
            assertEquals(
                QuicCandidateRace.Raced(live, listOf(QuicCandidateLoss.Abandoned(dark))),
                connection.candidateRace,
            )
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun aDarkIpv4CandidateCostsTheSameAsADarkIpv6One() =
        runTest {
            val dark = v4(1)
            val live = v6(2)
            val (connection, engine) = race(dark to Answer.Silent, live to Answer.Completes(120.milliseconds))

            assertEquals(370L, testScheduler.currentTime, "the race is not a function of which family is first")
            assertEquals(live, (connection.candidateRace as QuicCandidateRace.Raced).winner)
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun aFailingCandidateStartsTheNextAtOnceRatherThanAfterTheDelay() =
        runTest {
            val (connection, engine) =
                race(v4(1) to Answer.Fails(40.milliseconds), v6(2) to Answer.Completes(120.milliseconds))

            assertEquals(listOf(0L, 40L), engine.startedAt)
            assertEquals(160L, testScheduler.currentTime)
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun everyCandidateIsDialledForTheOnePeerIdentityAndNeverForItsAddress() =
        runTest {
            val (connection, engine) =
                race(
                    v6(1) to Answer.Silent,
                    v4(2) to Answer.Silent,
                    v4(3) to Answer.Completes(120.milliseconds),
                    serverName = "peer.example",
                )

            assertEquals(
                listOf("peer.example", "peer.example", "peer.example"),
                engine.identities,
                "a candidate list is many endpoints and ONE identity; dialling a literal as the server " +
                    "name would put it into SNI and fail verification against every certificate the peer has",
            )
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun aCandidateThatCompletesAfterTheRaceIsDecidedIsClosedAndReportedAsLate() =
        runTest {
            val late = v6(1)
            val live = v4(2)
            val (connection, engine) =
                race(late to Answer.CompletesRegardless(600.milliseconds), live to Answer.Completes(120.milliseconds))

            assertEquals(
                QuicCandidateRace.Raced(live, listOf(QuicCandidateLoss.ClosedAsLate(late))),
                connection.candidateRace,
                "a handshake that completes after the race is a second live connection to the peer, and " +
                    "the outcome has to say so — it is the one loss that leaves something to release",
            )
            assertEquals(2, engine.minted.size, "both candidates really did establish")
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun aCandidateThePacerNeverReachedIsReportedAsNeverAttempted() =
        runTest {
            val fast = v6(1)
            val untouched = v4(2)
            val (connection, engine) = race(fast to Answer.Completes(120.milliseconds), untouched to Answer.Silent)

            assertEquals(listOf(0L), engine.startedAt, "the winner answered inside the attempt delay")
            assertEquals(
                QuicCandidateRace.Raced(fast, listOf(QuicCandidateLoss.NeverAttempted(untouched))),
                connection.candidateRace,
            )
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun simultaneousPacingSendsEveryCandidatesFirstFlightAtOnce() =
        runTest {
            val (connection, engine) =
                race(
                    v6(1) to Answer.Silent,
                    v4(2) to Answer.Fails(30.milliseconds),
                    v4(3) to Answer.Completes(120.milliseconds),
                    pacing = ConnectPacing.Simultaneous,
                )

            assertEquals(
                listOf(0L, 0L, 0L),
                engine.startedAt,
                "a peer-to-peer connect punches every candidate's NAT at once; a stagger would delay " +
                    "exactly the packets the arrangement depends on",
            )
            assertEquals(120L, testScheduler.currentTime, "the third candidate's own handshake, with nothing added")
            assertNothingLeaked(engine, connection)
        }

    @Test
    fun aRejectedCertificateEndsTheRaceInsteadOfBeingRetriedAtEveryAddress() =
        runTest {
            val engine =
                ScriptedEngine(
                    this,
                    mapOf(
                        v6(1) to Answer.RejectsTheCertificate(80.milliseconds),
                        v4(2) to Answer.Completes(10.milliseconds),
                    ),
                )
            val thrown =
                assertFailsWith<QuicCloseException> {
                    engine.connectRacing(
                        QuicClientBinding.OwnSocket,
                        QuicPeer.Candidates(listOf(v6(1), v4(2)), "peer.example"),
                        options,
                        TransportConfig(connectPacing = ConnectPacing.Staggered(250.milliseconds)),
                        30.seconds,
                    )
                }

            assertEquals(QuicCloseReason.ByLocal(QuicError.CryptoError(48)), thrown.closeReason)
            assertEquals(
                listOf(0L),
                engine.startedAt,
                "the peer's certificate is the same at every endpoint, so the second candidate must " +
                    "never have been dialled — otherwise one honest certificate error becomes a list of them",
            )
            assertEquals(emptyList(), engine.open())
        }

    @Test
    fun aConnectRefusedForItsOwnArgumentsEndsTheRaceAtTheFirstCandidate() =
        runTest {
            val engine =
                ScriptedEngine(
                    this,
                    mapOf(v6(1) to Answer.RefusesTheArguments, v4(2) to Answer.Completes(10.milliseconds)),
                )
            assertFailsWith<EarlyDataProtocolNotOfferedException> {
                engine.connectRacing(
                    QuicClientBinding.OwnSocket,
                    QuicPeer.Candidates(listOf(v6(1), v4(2)), "peer.example"),
                    options,
                    TransportConfig(),
                    30.seconds,
                )
            }

            assertEquals(
                listOf(0L),
                engine.startedAt,
                "every attempt is built from the one QuicOptions the caller passed, so an argument the " +
                    "connect does not permit is refused identically at every endpoint — reporting it once " +
                    "per candidate would turn one programming error into a list of them",
            )
            assertEquals(emptyList(), engine.open())
        }

    @Test
    fun aSingleCandidateIsUnopposedRatherThanARaceWithNoLosers() =
        runTest {
            val only = v4(1)
            val engine = ScriptedEngine(this, mapOf(only to Answer.Completes(120.milliseconds)))
            val connection =
                engine.connectRacing(
                    QuicClientBinding.OwnSocket,
                    QuicPeer.Candidates(listOf(only), "peer.example"),
                    options,
                    TransportConfig(),
                    30.seconds,
                )

            assertEquals(QuicCandidateRace.Unopposed(only), connection.candidateRace)
            assertEquals(120L, testScheduler.currentTime)
        }

    @Test
    fun aRaceEveryCandidateLosesReportsTheLastFailure() =
        runTest {
            val engine =
                ScriptedEngine(
                    this,
                    mapOf(v6(1) to Answer.Fails(30.milliseconds), v4(2) to Answer.Fails(40.milliseconds)),
                )
            val thrown =
                assertFailsWith<QuicCloseException> {
                    engine.connectRacing(
                        QuicClientBinding.OwnSocket,
                        QuicPeer.Candidates(listOf(v6(1), v4(2)), "peer.example"),
                        options,
                        TransportConfig(),
                        30.seconds,
                    )
                }

            assertTrue(thrown.message!!.contains("192.0.2.2"), "the reported failure is the last one: ${thrown.message}")
            assertEquals(emptyList(), engine.open())
        }

    @Test
    fun twoPeersRacingEachOthersCandidateSetsBothConnectWithoutLeavingAnythingOpen() =
        runTest {
            // Each peer publishes three candidates and only the third works — a global address its NAT
            // does not carry, a stale server-reflexive pair, and the one that answers.
            fun candidatesOf(n: Int) = listOf(v6(n), v4(n), v4(n + 10, port = 4433))

            val alice = candidatesOf(1)
            val bob = candidatesOf(2)

            fun scriptFor(candidates: List<QuicEndpoint>) =
                mapOf(
                    candidates[0] to Answer.Silent,
                    candidates[1] to Answer.Fails(45.milliseconds),
                    candidates[2] to Answer.Completes(120.milliseconds),
                )

            val aliceEngine = ScriptedEngine(this, scriptFor(bob))
            val bobEngine = ScriptedEngine(this, scriptFor(alice))
            val config = TransportConfig(connectPacing = ConnectPacing.Simultaneous)

            val a =
                async {
                    aliceEngine.connectRacing(
                        QuicClientBinding.OwnSocket,
                        QuicPeer.Candidates(bob, "bob"),
                        options,
                        config,
                        30.seconds,
                    )
                }
            val b =
                async {
                    bobEngine.connectRacing(
                        QuicClientBinding.OwnSocket,
                        QuicPeer.Candidates(alice, "alice"),
                        options,
                        config,
                        30.seconds,
                    )
                }
            val toBob = a.await()
            val toAlice = b.await()

            assertEquals(
                120L,
                testScheduler.currentTime,
                "both peers punched all three candidates at once, so each paid one handshake — " +
                    "staggering would have put the working pair two attempt delays out",
            )
            val nominated = toBob.candidateRace as QuicCandidateRace.Raced
            assertEquals(bob[2], nominated.winner, "the nominated pair is the candidate that answered")
            assertEquals(
                listOf(QuicCandidateLoss.Abandoned(bob[0]), bob[1]),
                listOf(nominated.lost[0], (nominated.lost[1] as QuicCandidateLoss.HandshakeFailed).endpoint),
                "each losing candidate says why it is not the connection: the dark one was abandoned, " +
                    "the stale pair failed. Lost: ${nominated.lost}",
            )
            assertEquals(alice[2], (toAlice.candidateRace as QuicCandidateRace.Raced).winner)
            assertNothingLeaked(aliceEngine, toBob)
            assertNothingLeaked(bobEngine, toAlice)
        }
}
