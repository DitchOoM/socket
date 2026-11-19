package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicActivePathStats
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.conn.ArmedDeadline
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.FlightTail
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.QuicheWait
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.CountsSeen
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Statistics and stall reports read from a core connection, on request only. */
class CoreObservationTests {
    @Test
    fun statsCountTheEchoAndMeasureTheActivePathAndAnEndedConnectionSaysSo() =
        runBlocking {
            skipOnMissingNativeLib(CoreObservationTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val stream = client.connection.openStream()
                    val payload = BufferFactory.network().allocate(PAYLOAD).apply { repeat(PAYLOAD) { writeByte(1) } }
                    payload.resetForRead()
                    stream.write(payload, 10.seconds)
                    val back = stream.read(10.seconds)
                    if (back !is ReadResult.Data) fail("no echo: $back")
                    back.buffer.freeIfNeeded()

                    val stats = assertIs<QuicStatsRead.Read>(client.connection.stats()).stats
                    assertTrue(stats.packetsSent > 0 && stats.packetsReceived > 0, "$stats")
                    assertTrue(stats.bytesSent >= PAYLOAD && stats.bytesReceived >= PAYLOAD, "$stats")
                    assertEquals(1, stats.paths)
                    val path = assertIs<QuicActivePathStats.Measured>(stats.activePath)
                    assertTrue(path.smoothedRtt > Duration.ZERO && path.minRtt > Duration.ZERO, "$path")
                    assertTrue(path.congestionWindowBytes > 0 && path.pathMtuBytes >= MIN_QUIC_MTU, "$path")

                    client.connection.close(QuicError.NoError)
                    assertEquals(QuicStatsRead.Ended, client.connection.stats())
                }
            }
        }

    @Test
    fun aConnectionHeldInsideReportsNoAnswerAndOnceFreeReportsWhatAParkedReadWaitsOn() =
        runBlocking {
            skipOnMissingNativeLib(CoreObservationTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val stream = client.connection.openStream()
                    val parked = async { runCatching { stream.read(30.seconds) } }

                    val hold = HoldInside()
                    val before = client.conn.counts.seen()
                    client.conn.submit(hold, CallerPlace.HandToLoop)
                    assertTrue(hold.entered.await(10, TimeUnit.SECONDS), "the hold never got inside")
                    val held = client.connection.stallReport(STALL_BOUND)
                    assertIs<Life.Open>(held.life)
                    assertEquals(STALL_BOUND, assertIs<InsideAnswer.NoAnswer>(held.inside).waited)
                    hold.release.countDown()

                    val report = client.connection.stallReport(10.seconds)
                    assertIs<Life.Open>(report.life)
                    // Each of the hold and the two reports reached the connection handed to the loop, or left for the
                    // thread inside when the loop was in it (it serves the parked read's acknowledgements on its own).
                    val handedOff = { c: CountsSeen -> c.handedToLoop + c.leftForHolder }
                    assertTrue(
                        handedOff(report.counts) - handedOff(before) >= HANDED_OFF,
                        "the hold and both reports were handed to the loop or left for the thread inside",
                    )
                    val view = assertIs<InsideAnswer.Answered>(report.inside).view
                    assertEquals(1, view.liveStreams)
                    assertEquals(0, view.opensWaiting)
                    assertIs<QuicheWait.Due>(view.quiche, "an established connection always has quiche's idle timer")
                    assertIs<ArmedDeadline.Due>(view.deadline, "the loop must hold a deadline for quiche's timer")
                    val tail = assertIs<FlightTail.Last>(view.flight)
                    assertTrue(tail.held > 0 && tail.ago >= Duration.ZERO, "flight tail $tail")
                    assertIs<QuicStatsRead.Read>(view.stats)

                    client.connection.close(QuicError.NoError)
                    parked.await()
                    assertIs<InsideAnswer.Ended>(client.connection.stallReport(10.seconds).inside)
                }
            }
        }

    private class HoldInside : ConnWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun run(inside: Inside) {
            entered.countDown()
            release.await()
        }

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val PAYLOAD = 64
        const val MIN_QUIC_MTU = 1200L
        val STALL_BOUND = 200.milliseconds

        /** The hold and the two stall reports. */
        const val HANDED_OFF = 3L
    }
}
