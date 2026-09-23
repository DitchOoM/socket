package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectPacing
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
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
            val transport =
                TransportConfig(
                    bufferFactory = BufferFactory.deterministic(),
                    connectPacing = ConnectPacing.Simultaneous,
                )
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
                        val candidates =
                            listOf(
                                QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), first.port),
                                QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), second.port),
                            )
                        val peer = QuicPeer.Candidates(candidates, serverName = "localhost")

                        // Pays for the quiche dlopen, the pools and the dispatcher threads before the
                        // baseline, so none of it can be mistaken for a leak.
                        repeat(WARMUP_RACES) { race(peer, options, transport) }

                        val before = openFileDescriptors()
                        var raced = 0
                        repeat(RACES) { if (race(peer, options, transport)) raced++ }
                        val after = openFileDescriptors()

                        assertEquals(
                            RACES,
                            raced,
                            "every race must really have had two live candidates for this measurement to " +
                                "mean anything: a race whose loser failed instead of establishing exercises " +
                                "the ordinary failure path, not the cancelled-or-late one this is about.",
                        )
                        val leaked = after - before
                        assertTrue(
                            leaked <= MAX_TOLERATED_DESCRIPTORS,
                            "a losing candidate leaks its UDP socket: $RACES races, each with one candidate " +
                                "that lost after establishing, left $leaked descriptors behind " +
                                "(before=$before after=$after), ${"%.1f".format(leaked.toDouble() / RACES)} per " +
                                "race; at most $MAX_TOLERATED_DESCRIPTORS in total is expected. A connect that " +
                                "is cancelled between establishment and being returned owns a live connection " +
                                "nobody else will ever close.",
                        )
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
            val transport =
                TransportConfig(
                    bufferFactory = BufferFactory.deterministic(),
                    connectPacing = ConnectPacing.Simultaneous,
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
                        val peer =
                            QuicPeer.Candidates(
                                listOf(
                                    QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), first.port),
                                    QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), second.port),
                                ),
                                serverName = "localhost",
                            )
                        repeat(WARMUP_RACES) { race(peer, options, transport) }

                        val before = openFileDescriptors()
                        var raced = 0
                        repeat(RACES) { if (race(peer, options, transport)) raced++ }
                        val after = openFileDescriptors()

                        assertEquals(RACES, raced, "every race must have had two live, pinned candidates")
                        val leaked = after - before
                        assertTrue(
                            leaked <= MAX_TOLERATED_DESCRIPTORS,
                            "a losing candidate cancelled while verifying the peer's certificate leaks its " +
                                "whole connection: $RACES races left $leaked descriptors behind " +
                                "(before=$before after=$after). Establishment hands the connection its own " +
                                "teardown, but until the builder RETURNS it nothing else will ever call it.",
                        )
                    } finally {
                        jobs.forEach { it.cancel() }
                    }
                }
            }
        }

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

    /** One race; `true` when both candidates were live, which is what the measurement requires. */
    private suspend fun race(
        peer: QuicPeer,
        options: QuicOptions,
        transport: TransportConfig,
    ): Boolean =
        withQuicConnection(peer, options, transport, timeout = 20.seconds) { race ->
            val loss = (race as QuicCandidateRace.Raced).lost.single()
            loss is QuicCandidateLoss.ClosedAsLate || loss is QuicCandidateLoss.Abandoned
        }

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private fun openFileDescriptors(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean).openFileDescriptorCount

    private companion object {
        const val WARMUP_RACES = 2

        /** Enough that a four-descriptor-per-race leak is an order of magnitude clear of the tolerance. */
        const val RACES = 8

        /** Post-fix the measured delta is 0; the tolerance absorbs an unrelated descriptor the JVM may open. */
        const val MAX_TOLERATED_DESCRIPTORS = 4
    }
}
