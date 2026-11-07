package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectPacing
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **A candidate that loses a connect race returns everything it took.**
 *
 * Racing several endpoints means every connect but one ends in a way no connect used to end: not by
 * failing, but by being cancelled, or by establishing a connection the caller then does not want. Each
 * losing attempt has already opened a connected UDP socket — the JVM one costs four descriptors, the
 * `DatagramChannel` plus its NIO `Selector`'s kqueue descriptor and wakeup pipe pair — and nothing
 * closes those unless the builder's teardown ladder names the stage the cancel landed in.
 *
 * Two live peers, so **both** candidates really establish and the loser is a whole live connection
 * rather than a half-built one; [ConnectPacing.Simultaneous] so they establish together and the loser is
 * decided by microseconds, which is what makes it land in the cancelled-or-late window rather than in
 * the ordinary failure path every earlier test covers.
 *
 * ## Why every candidate's first flight goes through a [FirstFlightGate]
 * [ConnectPacing.Simultaneous] launches both attempts in one pass, but launching is not running: each
 * attempt is a coroutine that still has to be given a thread. If the first one's whole handshake finishes
 * before the second is scheduled — one preempted worker on a loaded runner is enough — the race is
 * decided before the second attempt starts, it is cancelled unstarted, and it is reported, correctly, as
 * [QuicCandidateLoss.NeverAttempted]. It opened nothing, so it exercises nothing this test is about. That
 * is how one race in eight came out without two live candidates on a CI runner, with nothing wrong in the
 * code under test.
 *
 * So the test makes "both candidates are live" a fact rather than a likelihood: each server sits behind
 * a relay that holds every candidate's datagrams until both candidates have sent one. Neither handshake
 * can complete before the other candidate has opened its socket and sent its Initial, however the
 * attempts are scheduled — and from then on both handshakes run concurrently, as before.
 *
 * ## Why descriptors and not a counter
 * The same argument [FailedConnectFdLeakTest] makes: an instrument that counts `close()` calls reports
 * what it is given, and the whole defect class here is a path that never reaches the call. Descriptors
 * returned to the process cannot be produced by a stub or a spy. This test is JVM-only for that reason —
 * it is the platform with a first-class descriptor count — while `QuicCandidateRaceTestSuite` carries
 * the behaviour to every backend.
 */
class RaceLoserFdLeakTest {
    @Test
    fun theLosingCandidateOfEveryRaceGivesItsDescriptorsBack() =
        runQuicTest(timeout = 120.seconds) {
            val options = QuicOptions(alpnProtocols = listOf("race-fd"), verifyPeer = false)
            val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

            withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                val first = this
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                    val second = this
                    val jobs =
                        listOf(
                            launch { first.connections { awaitCancellation() } },
                            launch { second.connections { awaitCancellation() } },
                        )
                    try {
                        FirstFlightGate(listOf(first.port, second.port)).use { gate ->
                            val outcome = measureRaces(gate, options)
                            assertEquals(
                                emptyList<String>(),
                                outcome.withoutTwoLiveCandidates,
                                "every race must really have had two live candidates for this measurement to " +
                                    "mean anything: a race whose loser failed instead of establishing exercises " +
                                    "the ordinary failure path, not the cancelled-or-late one this is about.",
                            )
                            assertTrue(
                                outcome.leaked <= MAX_TOLERATED_DESCRIPTORS,
                                "a losing candidate leaks its UDP socket: $RACES races, each with one candidate " +
                                    "that lost after establishing, left ${outcome.leaked} descriptors behind " +
                                    "(${outcome.counts}), ${"%.1f".format(outcome.leaked.toDouble() / RACES)} per " +
                                    "race; at most $MAX_TOLERATED_DESCRIPTORS in total is expected. A connect that " +
                                    "is cancelled between establishment and being returned owns a live connection " +
                                    "nobody else will ever close.",
                            )
                        }
                    } finally {
                        jobs.forEach { it.cancel() }
                    }
                }
            }
        }

    /**
     * The same measurement with the peer authenticated by certificate hash — the peer-to-peer mode, and
     * the one that reaches a window the unpinned race cannot.
     *
     * Hash verification reads the peer's certificate through the driver, so it **suspends** after the
     * handshake has completed and before the connection has been handed back. That is the only moment a
     * losing candidate can be cancelled while owning a fully established connection, and the only exit
     * the teardown ladder's `Established` stage answers for.
     */
    @Test
    fun aPinnedPeerLosingARaceAlsoGivesItsDescriptorsBack() =
        runQuicTest(timeout = 120.seconds) {
            val tls = fixtureTlsConfig("pinned")
            val options =
                QuicOptions(
                    alpnProtocols = listOf("race-fd"),
                    serverCertificateHashes = listOf(fixtureLeafHash("pinned")),
                )

            withQuicServer(port = 0, tlsConfig = tls, quicOptions = QuicOptions(alpnProtocols = listOf("race-fd"))) {
                val first = this
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = QuicOptions(alpnProtocols = listOf("race-fd"))) {
                    val second = this
                    val jobs =
                        listOf(
                            launch { first.connections { awaitCancellation() } },
                            launch { second.connections { awaitCancellation() } },
                        )
                    try {
                        FirstFlightGate(listOf(first.port, second.port)).use { gate ->
                            val outcome = measureRaces(gate, options)
                            assertEquals(
                                emptyList<String>(),
                                outcome.withoutTwoLiveCandidates,
                                "every race must have had two live, pinned candidates",
                            )
                            assertTrue(
                                outcome.leaked <= MAX_TOLERATED_DESCRIPTORS,
                                "a losing candidate cancelled while verifying the peer's certificate leaks its " +
                                    "whole connection: $RACES races left ${outcome.leaked} descriptors behind " +
                                    "(${outcome.counts}). Establishment hands the connection its own " +
                                    "teardown, but until the builder RETURNS it nothing else will ever call it.",
                            )
                        }
                    } finally {
                        jobs.forEach { it.cancel() }
                    }
                }
            }
        }

    /** What [RACES] measured races left behind, and every one of them that did not have two live candidates. */
    private class Measured(
        val before: Long,
        val after: Long,
        val withoutTwoLiveCandidates: List<String>,
    ) {
        val leaked: Long get() = after - before
        val counts: String get() = "before=$before after=$after"
    }

    private suspend fun measureRaces(
        gate: FirstFlightGate,
        options: QuicOptions,
    ): Measured {
        val peer =
            QuicPeer.Candidates(
                gate.ports.map { QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), it) },
                serverName = "localhost",
            )
        val transport =
            TransportConfig(
                bufferFactory = BufferFactory.deterministic(),
                connectPacing = ConnectPacing.Simultaneous,
            )
        // Pays for the quiche dlopen, the pools and the dispatcher threads before the baseline, so none
        // of it can be mistaken for a leak.
        repeat(WARMUP_RACES) { race(gate, peer, options, transport) }

        val before = openFileDescriptors()
        val withoutTwoLiveCandidates =
            (1..RACES).mapNotNull { n ->
                race(gate, peer, options, transport).takeUnless { it.hadTwoLiveCandidates() }?.let { "race $n: $it" }
            }
        val after = openFileDescriptors()
        return Measured(before, after, withoutTwoLiveCandidates)
    }

    /**
     * One race, gated so both candidates are live before either can win. Returns how its loser lost —
     * the whole [QuicCandidateLoss], so a race that did not count says exactly what happened to it.
     */
    private suspend fun race(
        gate: FirstFlightGate,
        peer: QuicPeer,
        options: QuicOptions,
        transport: TransportConfig,
    ): QuicCandidateLoss {
        gate.arm()
        val loss =
            withQuicConnection(peer, options, transport, timeout = 20.seconds) { race ->
                (race as QuicCandidateRace.Raced).lost.single()
            }
        // The gate opening is what makes the loser live; a race it never opened for proves nothing.
        assertTrue(gate.opened, "the race finished without both candidates' first flights reaching the gate: $loss")
        return loss
    }

    /** The two fates a loser can only meet after it has opened its socket and its handshake was running. */
    private fun QuicCandidateLoss.hadTwoLiveCandidates(): Boolean =
        this is QuicCandidateLoss.ClosedAsLate || this is QuicCandidateLoss.Abandoned

    private fun fixtureTlsConfig(name: String) = QuicTlsConfig(certChainPath = certPath("$name.crt"), privKeyPath = certPath("$name.key"))

    /** The fixture's own recorded leaf SHA-256, so the pin is not computed by the code it pins. */
    private fun fixtureLeafHash(name: String): CertificateHash {
        val hex =
            java.io
                .File(certPath("$name.sha256"))
                .readText()
                .trim()
                .substringAfterLast(' ')
        val buf = BufferFactory.Default.allocate(hex.length / 2)
        hex.chunked(2).forEach { buf.writeByte(it.toInt(16).toByte()) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private fun openFileDescriptors(): Long = OpenHandleMeter.forThisProcess().openHandles()

    private companion object {
        const val WARMUP_RACES = 2

        /** Enough that a four-descriptor-per-race leak is an order of magnitude clear of the tolerance. */
        const val RACES = 8

        /** Post-fix the measured delta is 0; the tolerance absorbs an unrelated descriptor the JVM may open. */
        const val MAX_TOLERATED_DESCRIPTORS = 4
    }
}

/**
 * One relay in front of each server, holding every candidate's datagrams from [arm] until each candidate
 * has sent at least one, then releasing them all at once.
 *
 * That makes the race's precondition a state rather than a matter of scheduling: no handshake can finish
 * — its Initial has not even reached its server — until every candidate has opened its socket and sent
 * its first flight. Once open the gate is a plain pass-through, so the handshakes themselves still race
 * each other exactly as they would without it.
 *
 * Opened and closed outside the measured window: its sockets and selectors are the test's, not a race's.
 */
private class FirstFlightGate(
    serverPorts: List<Int>,
) : AutoCloseable {
    private val lock = Any()
    private val relays = ArrayList<SelectorDatagramRelay>(serverPorts.size)
    private val held = List(serverPorts.size) { ArrayList<ByteArray>() }
    private val heard = BooleanArray(serverPorts.size)
    private var armed = false

    init {
        serverPorts.forEachIndexed { index, port ->
            relays +=
                SelectorDatagramRelay(
                    serverPort = port,
                    maxDatagram = MAX_DATAGRAM,
                    onClientToServer = { buf, _ -> fromCandidate(index, buf) },
                    onServerToClient = { buf, _ -> relays[index].writeToClient(buf) },
                )
        }
        relays.forEach { it.start() }
    }

    /** Where the candidates are dialled, in server order. */
    val ports: List<Int> = relays.map { it.proxyPort }

    /** Whether every candidate has been heard from since the last [arm]. */
    val opened: Boolean get() = synchronized(lock) { !armed }

    /** Close the gate for the next race. */
    fun arm() =
        synchronized(lock) {
            armed = true
            heard.fill(false)
            held.forEach { it.clear() }
        }

    // On each relay's own pump thread. Under the lock throughout, so a datagram passed straight through
    // can never overtake one being released from another thread.
    private fun fromCandidate(
        index: Int,
        buf: ByteBuffer,
    ) = synchronized(lock) {
        if (!armed) {
            relays[index].writeToServer(buf)
            return@synchronized
        }
        held[index] += ByteArray(buf.remaining()).also { buf.get(it) }
        heard[index] = true
        if (heard.all { it }) {
            armed = false
            held.forEachIndexed { i, datagrams ->
                datagrams.forEach { relays[i].writeToServerBytes(it) }
                datagrams.clear()
            }
        }
    }

    override fun close() = relays.forEach { it.close() }

    private companion object {
        const val MAX_DATAGRAM = 65_535
    }
}
