package com.ditchoom.socket.transport

import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportsExhausted
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * RFC_TRANSPORT_FALLBACK §5 staggered family racing + §6 per-network learning, all on virtual time
 * (`runTest`): stagger and per-attempt timeouts advance the scheduler, so every timing assertion is
 * exact, not sleep-based.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // TestScope.currentTime
class FallbackRacingTest {
    private val config = TransportConfig(connectTimeout = 15.seconds)
    private val stagger = 250.milliseconds
    private val wifi = NetworkId.Link(NetworkKind.Wifi, 7)
    private val cellular = NetworkId.Link(NetworkKind.Cellular, 8)

    private fun timeout() = SocketTimeoutException("t")

    private fun refused() = SocketConnectionException.Refused("h", 1)

    // ---- §5 racing ----------------------------------------------------------------------------

    @Test
    fun tcpLaneStartsAfterTheStaggerNotAfterTheFullUdpTimeout() =
        runTest {
            val quic = ScriptedTransport("QUIC", ScriptedTransport.Outcome.Hang, family = TransportFamily.Udp)
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(quic, tcp), stagger = stagger).connect("h", 1, config)
            assertTrue(stream.isOpen)
            assertEquals(1, tcp.connectCount)
            // A UDP block costs the stagger delay, not the 15s per-attempt timeout (§5).
            assertEquals(250, currentTime)
        }

    @Test
    fun udpWinnerBeforeTheStaggerNeverStartsTheTcpLane() =
        runTest {
            val quic = ScriptedTransport("QUIC", ScriptedTransport.Outcome.Succeed, family = TransportFamily.Udp)
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(quic, tcp), stagger = stagger).connect("h", 1, config)
            assertTrue(stream.isOpen)
            assertEquals(0, tcp.connectCount, "the chase lane must be cancelled while still gated")
            assertEquals(0, currentTime)
        }

    @Test
    fun headLaneExhaustionReleasesTheChaseLaneBeforeTheStagger() =
        runTest {
            // QUIC refuses instantly → the head lane exhausts at t=0 → the gate opens early (§5).
            val quic = ScriptedTransport("QUIC", ScriptedTransport.Outcome.Fail(refused()), family = TransportFamily.Udp)
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(quic, tcp), stagger = stagger).connect("h", 1, config)
            assertTrue(stream.isOpen)
            assertEquals(1, tcp.connectCount)
            assertEquals(0, currentTime, "a fast-failing head must not make the chase wait the full stagger")
        }

    @Test
    fun sequentialWithinALane() =
        runTest {
            // Both UDP rungs refuse in order on the head lane before TCP (still gated at t=0 exhaustion).
            val quic = ScriptedTransport("QUIC", ScriptedTransport.Outcome.Fail(refused()), family = TransportFamily.Udp)
            val wt = ScriptedTransport("WT", ScriptedTransport.Outcome.Fail(refused()), family = TransportFamily.Udp)
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(quic, wt, tcp), stagger = stagger).connect("h", 1, config)
            assertTrue(stream.isOpen)
            assertEquals(1, quic.connectCount)
            assertEquals(1, wt.connectCount)
            assertEquals(1, tcp.connectCount)
        }

    @Test
    fun fatalOnTheChaseLaneCollapsesTheWholeRace() =
        runTest {
            val quic = ScriptedTransport("QUIC", ScriptedTransport.Outcome.Hang, family = TransportFamily.Udp)
            val tcp =
                ScriptedTransport(
                    "TCP",
                    ScriptedTransport.Outcome.Fail(
                        SSLHandshakeFailedException("bad cert", reason = ConnectionFailureReason.TlsBadCertificate),
                    ),
                )
            assertFailsWith<SSLHandshakeFailedException> {
                FallbackTransport(listOf(quic, tcp), stagger = stagger).connect("h", 1, config)
            }
            // The fatal surfaced when the chase ran (t=stagger) — the hung UDP lane was cancelled, not awaited.
            assertEquals(250, currentTime)
        }

    @Test
    fun bothLanesExhaustedAggregatesAllFailures() =
        runTest {
            val quic = ScriptedTransport("QUIC", ScriptedTransport.Outcome.Fail(refused()), family = TransportFamily.Udp)
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Fail(refused()))
            val e =
                assertFailsWith<TransportsExhausted> {
                    FallbackTransport(listOf(quic, tcp), stagger = stagger).connect("h", 1, config)
                }
            assertEquals(2, e.failures.size)
        }

    @Test
    fun raceLoserThatStillConnectsIsClosedNotLeaked() =
        runTest {
            // Both lanes complete a connect at the same virtual instant (t=300): whichever loses the
            // winner slot must close its stream — exactly one live connection leaves the race.
            val quic =
                ScriptedTransport(
                    "QUIC",
                    ScriptedTransport.Outcome.SucceedAfter(300.milliseconds),
                    family = TransportFamily.Udp,
                )
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.SucceedAfter(50.milliseconds))
            val stream = FallbackTransport(listOf(quic, tcp), stagger = stagger).connect("h", 1, config)
            assertTrue(stream.isOpen)
            val produced = quic.streams + tcp.streams
            assertEquals(2, produced.size, "both lanes should have completed a connect")
            assertEquals(1, produced.count { it.isOpen }, "the losing connection must be closed")
        }

    @Test
    fun staggerWithASingleFamilyChainFallsBackToSequential() =
        runTest {
            val a = ScriptedTransport("A", ScriptedTransport.Outcome.Fail(refused()))
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(a, b), stagger = stagger).connect("h", 1, config)
            assertTrue(stream.isOpen)
            assertEquals(1, a.connectCount)
            assertEquals(1, b.connectCount)
        }

    // ---- §6 per-network family contrast ---------------------------------------------------------

    @Test
    fun wholeUdpFamilyTimeoutWithTcpSuccessDemotesUdpOnThisNetwork() =
        runTest {
            val quic =
                ScriptedTransport(
                    "QUIC",
                    ScriptedTransport.Outcome.Fail(timeout()),
                    ScriptedTransport.Outcome.Succeed,
                    family = TransportFamily.Udp,
                )
            val wt =
                ScriptedTransport(
                    "WT",
                    ScriptedTransport.Outcome.Fail(timeout()),
                    ScriptedTransport.Outcome.Succeed,
                    family = TransportFamily.Udp,
                )
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(quic, wt, tcp), networkId = { wifi })

            fallback.connect("h", 1, config) // whole UDP family times out, TCP connects → per-network demote
            fallback.connect("h", 1, config) // same network: TCP lane first, UDP skipped

            assertEquals(1, quic.connectCount, "QUIC must be demoted on this network after the family contrast")
            assertEquals(1, wt.connectCount)
            assertEquals(2, tcp.connectCount)
        }

    @Test
    fun networkChangeInvalidatesThePerNetworkDemotion() =
        runTest {
            var network: NetworkId = wifi
            val quic =
                ScriptedTransport(
                    "QUIC",
                    ScriptedTransport.Outcome.Fail(timeout()),
                    ScriptedTransport.Outcome.Succeed,
                    family = TransportFamily.Udp,
                )
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(quic, tcp), networkId = { network })

            fallback.connect("h", 1, config) // UDP-blocked wifi learned
            network = cellular
            fallback.connect("h", 1, config) // new network: demotion keyed to wifi doesn't apply → QUIC re-probed

            assertEquals(2, quic.connectCount, "a network change must re-probe the UDP family")
        }

    @Test
    fun udpTimeoutWithoutTcpSuccessRecordsNothing() =
        runTest {
            // Everything timed out → likely a general outage, not a UDP-blocking path: never poison.
            val quic =
                ScriptedTransport(
                    "QUIC",
                    ScriptedTransport.Outcome.Fail(timeout()),
                    ScriptedTransport.Outcome.Succeed,
                    family = TransportFamily.Udp,
                )
            val tcp =
                ScriptedTransport(
                    "TCP",
                    ScriptedTransport.Outcome.Fail(timeout()),
                    ScriptedTransport.Outcome.Succeed,
                )
            val fallback = FallbackTransport(listOf(quic, tcp), networkId = { wifi })

            assertFailsWith<TransportsExhausted> { fallback.connect("h", 1, config) }
            fallback.connect("h", 1, config)

            assertEquals(2, quic.connectCount, "QUIC must stay preferred — a full outage says nothing about UDP")
        }

    @Test
    fun udpRefusedIsPerHostNotPerNetwork() =
        runTest {
            // A refused UDP rung is a *server* signal (per-host): the family contrast must not also
            // record a per-network entry, so the same server on a NEW network is still demoted per-host
            // while a DIFFERENT host on the old network re-probes UDP.
            var network: NetworkId = wifi
            val quic =
                ScriptedTransport(
                    "QUIC",
                    ScriptedTransport.Outcome.Fail(refused()),
                    ScriptedTransport.Outcome.Succeed,
                    family = TransportFamily.Udp,
                )
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(quic, tcp), networkId = { network })

            fallback.connect("h", 1, config) // QUIC refused by host "h" → per-host demote only
            network = cellular
            fallback.connect("h", 1, config) // same host, new network: per-host demotion still applies

            assertEquals(1, quic.connectCount, "per-host demotion must survive a network change")
        }

    @Test
    fun udpTimeoutWithUnidentifiedNetworkRecordsNothing() =
        runTest {
            val quic =
                ScriptedTransport(
                    "QUIC",
                    ScriptedTransport.Outcome.Fail(timeout()),
                    ScriptedTransport.Outcome.Succeed,
                    family = TransportFamily.Udp,
                )
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(quic, tcp)) // networkId defaults to Unidentified

            fallback.connect("h", 1, config)
            fallback.connect("h", 1, config)

            assertEquals(2, quic.connectCount, "no network identity → the per-network scope is off (RFC §12)")
        }

    // ---- networkId stamping ---------------------------------------------------------------------

    @Test
    fun producedNetworkIdIsStampedOntoTheConnectConfig() =
        runTest {
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed)
            FallbackTransport(listOf(tcp), networkId = { wifi }).connect("h", 1, TransportConfig())
            assertEquals(wifi, tcp.seenConfigs.single().networkId)
        }

    @Test
    fun explicitConfigNetworkIdWinsOverTheProducer() =
        runTest {
            val tcp = ScriptedTransport("TCP", ScriptedTransport.Outcome.Succeed)
            FallbackTransport(listOf(tcp), networkId = { wifi }).connect("h", 1, TransportConfig(networkId = cellular))
            assertEquals(cellular, tcp.seenConfigs.single().networkId)
        }

    // ---- default cache --------------------------------------------------------------------------

    @Test
    fun defaultCacheLearnsAcrossConnects() =
        runTest {
            // No cache argument: the production default must remember the per-host refusal.
            val a =
                ScriptedTransport(
                    "A",
                    ScriptedTransport.Outcome.Fail(refused()),
                    ScriptedTransport.Outcome.Succeed,
                )
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(a, b))

            fallback.connect("h", 1, config)
            fallback.connect("h", 1, config)

            assertEquals(1, a.connectCount, "the default cache must demote the refused rung")
            assertEquals(2, b.connectCount)
        }
}
