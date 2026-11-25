package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.path.PathSilence
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.path.SilenceRule
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A client's silence watch allocates nothing: not per sample of the active path, not per arrival, not per wake it
 * offers, and not when a run grows, is declared and is answered, since each state is the watch's own object filled in
 * place. The backend's read of the active path allocates nothing either, on whichever binding `-PquicheJvmBackend` chose.
 */
class PathSilenceAllocationTests {
    @Test
    fun aSampleAnArrivalAndAWakeAllocateNothing() {
        val api = StubQuicheApi()
        // One table per expiry count, made up front: the stub hands back what it holds.
        val paths = Array(EXPIRIES + 1) { listOf(pathStats(it.toLong())) }
        api.pathStats = paths[0]
        val cell = PathNewsCell()
        val silence = PathSilence(QuicheConnRef(api, QuicheConn(1L)), SilenceRule(Silence.On, Span(CAP), cell))
        val sink = WakeSink()
        silence.baseline()
        var now = 0L
        var declared = 0

        fun cycle() {
            // A run builds expiry by expiry, is judged at each, wakes for its floor, is declared, and an arrival answers it.
            for (expiry in 1..EXPIRIES) {
                api.pathStats = paths[expiry]
                now += STEP
                silence.sample(Nanos(now))
                silence.verdict(Nanos(now))
                silence.offerWake(sink, 1)
                sink.reset()
                silence.unrecorded()
            }
            if (cell.read() is PathNews.Silent) declared++
            now += STEP
            silence.answered(Nanos(now))
            silence.handed(Nanos(now), now)
            silence.probe(Nanos(now), 1, 0L)
            silence.unrecorded()
            // quiche's counter starts again from zero on a path at a new index: a baseline, not a delta.
            api.pathStats = paths[0]
            silence.sample(Nanos(now))
        }
        repeat(WARM_UP) { cycle() }
        val bytes = measured { repeat(CYCLES) { cycle() } }
        println("[core silence watch allocation] %.2f B per cycle of %d samples, a verdict and an answer".format(bytes / CYCLES, EXPIRIES))
        assertEquals(WARM_UP + CYCLES, declared, "every cycle's run was declared")
        assertTrue(bytes / CYCLES < 1.0, "the silence watch allocated %.2f B per cycle".format(bytes / CYCLES))
    }

    @Test
    fun theBackendsReadOfTheActivePathAllocatesNothing() =
        runBlocking {
            val env = jvmMigrationSimEnv()
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished(30.milliseconds)
                var sum = 0L
                repeat(WARM_UP) { sum += env.api.connActivePathProbes(pair.client) }
                val bytes = measured { repeat(CYCLES) { sum += env.api.connActivePathProbes(pair.client) } }
                println("[core active path read allocation] %.2f B per read on %s".format(bytes / CYCLES, env.api::class.simpleName))
                assertTrue(sum >= 0L, "the client has an active path")
                assertTrue(bytes / CYCLES < 1.0, "a read of the active path allocated %.2f B".format(bytes / CYCLES))
            } finally {
                pair.close()
            }
        }

    private inline fun measured(block: () -> Unit): Double {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val self = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(self)
        block()
        return (threads.getThreadAllocatedBytes(self) - before).toDouble()
    }

    private companion object {
        const val EXPIRIES = 4
        const val STEP = 1_000_000_000L
        const val CAP = 30_000_000_000L
        const val WARM_UP = 20_000
        const val CYCLES = 20_000

        fun pathStats(probes: Long) =
            QuicPathStats(
                validationState = 0,
                active = true,
                recv = 0,
                sent = 0,
                lost = 0,
                retrans = 0,
                totalPtoCount = probes,
                rtt = Duration.ZERO,
                minRtt = Duration.ZERO,
                maxRtt = Duration.ZERO,
                rttvar = Duration.ZERO,
                cwnd = 0,
                sentBytes = 0,
                recvBytes = 0,
                lostBytes = 0,
                streamRetransBytes = 0,
                pmtu = 0,
                deliveryRate = 0,
            )
    }
}
