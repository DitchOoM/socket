package com.ditchoom.socket.testkit.walk

import com.ditchoom.socket.testkit.echo.EchoLivenessTotals
import com.ditchoom.socket.testkit.echo.RunLiveness
import com.ditchoom.socket.testkit.echo.SessionEnd
import com.ditchoom.socket.testkit.migration.PoolProbeHistory
import com.ditchoom.socket.testkit.migration.RunVerdict
import com.ditchoom.socket.testkit.migration.forRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Samsung, 2026-09-26 walk, v6 lane: the phone had no IPv6 route, so 215 of 219 connect attempts
 * failed at the route probe before any handshake. The probe counted each as a connection, gave each an
 * `ECHO-LIVENESS … LIVE` of its own, and rolled them into `connections=219`.
 */
class ConnectAttemptTests {
    private val v6 = WalkTarget("2001:db8::1", 44433)

    @Test
    fun anAttemptThatNeverConnectedIsNotAConnection() {
        val outcome = ConnectAttempt().failed(ConnectFailure.UnresolvedRoute, SessionEnd.ScopeFailed, at = 15.seconds)

        val never = assertIs<AttemptOutcome.NeverEstablished>(outcome)
        assertEquals("CONNECT-NEVER-ESTABLISHED n=215 family=v6 reason=UnresolvedRoute", never.line(215, v6))
    }

    @Test
    fun aConnectionWhoseScopeThrewKeepsItsSession() {
        val attempt = ConnectAttempt()
        attempt.established(at = 0.seconds)

        val outcome = attempt.failed(ConnectFailure.Threw("IllegalStateException"), SessionEnd.ScopeFailed, at = 5.minutes)

        val established = assertIs<AttemptOutcome.Established>(outcome)
        assertEquals(SessionEnd.ScopeFailed, established.report.end)
    }

    /** #689: a connection that dies cancels its scope; the session records the death, not a scope failure. */
    @Test
    fun aConnectionThatDiedUnderItsScopeEndsConnectionDead() {
        val attempt = ConnectAttempt()
        attempt.established(at = 0.seconds)

        val outcome = attempt.failed(ConnectFailure.Threw("QuicCloseException"), SessionEnd.ConnectionDead, at = 5.minutes)

        assertEquals(SessionEnd.ConnectionDead, assertIs<AttemptOutcome.Established>(outcome).report.end)
    }

    /** A close during the handshake is still an attempt that never established, whatever the session would have said. */
    @Test
    fun aCloseBeforeTheHandshakeCompletedIsNeverEstablished() {
        val outcome = ConnectAttempt().failed(ConnectFailure.Threw("QuicCloseException"), SessionEnd.ConnectionDead, at = 3.seconds)

        assertEquals(AttemptOutcome.NeverEstablished(ConnectFailure.Threw("QuicCloseException")), outcome)
    }

    @Test
    fun theSamsungV6LaneIsFourConnectionsOutOf219Attempts() {
        val attempts = AttemptTotals()
        val liveness = EchoLivenessTotals()
        repeat(219) { i ->
            val attempt = ConnectAttempt()
            val outcome =
                if (i < 4) {
                    attempt.established(at = 0.seconds).close(SessionEnd.ConnectionDead, at = 1.minutes).let {
                        AttemptOutcome.Established(it)
                    }
                } else {
                    attempt.failed(ConnectFailure.UnresolvedRoute, SessionEnd.ScopeFailed, at = 15.seconds)
                }
            attempts.absorb(outcome)
            when (outcome) {
                is AttemptOutcome.Established -> liveness.absorb(i + 1, outcome.report)
                is AttemptOutcome.NeverEstablished -> Unit
            }
        }

        assertEquals("CONNECT-TOTALS attempts=219 established=4 neverEstablished=[UnresolvedRoute=215]", attempts.line)
        assertEquals(4, assertIs<RunLiveness.Live>(liveness.verdict()).connections)
    }

    @Test
    fun aLaneThatNeverConnectedHasNoVerdictToGive() {
        val attempts = AttemptTotals()
        repeat(3) { attempts.absorb(ConnectAttempt().failed(ConnectFailure.UnresolvedRoute, SessionEnd.ScopeFailed, at = 15.seconds)) }

        val run = PoolProbeHistory().verdict().forRun(connections = 0, liveness = EchoLivenessTotals().verdict())

        assertEquals("CONNECT-TOTALS attempts=3 established=0 neverEstablished=[UnresolvedRoute=3]", attempts.line)
        assertIs<RunVerdict.NoConnection>(run)
    }

    @Test
    fun laneTotalsSumIntoTheRun() {
        val v4 =
            AttemptTotals().apply {
                absorb(ConnectAttempt().failed(ConnectFailure.Threw("QuicCloseException"), SessionEnd.ConnectionDead, 1.seconds))
            }
        val v6 =
            AttemptTotals().apply {
                absorb(
                    ConnectAttempt().failed(ConnectFailure.UnresolvedRoute, SessionEnd.ScopeFailed, 1.seconds),
                )
            }

        val run =
            AttemptTotals().apply {
                add(v4)
                add(v6)
            }

        assertEquals("CONNECT-TOTALS attempts=2 established=0 neverEstablished=[QuicCloseException=1,UnresolvedRoute=1]", run.line)
    }
}
