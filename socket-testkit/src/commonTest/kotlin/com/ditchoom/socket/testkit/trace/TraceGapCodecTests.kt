package com.ditchoom.socket.testkit.trace

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReachResolution
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The `NET_GAP` line: `parse(e.toString()) == e`, the exact wire shape, and its place in the sealed
 * model. The gap marker is only worth anything if it survives the sink boundary intact — a trace is
 * written as text and read back as types, and everything downstream reads the types.
 */
class TraceGapCodecTests {
    private val wifi = NetworkId.Link(NetworkKind.Wifi, handle = 441492361229L)

    @Test
    fun netGapRoundTripsThroughTheLineFormat() {
        val events =
            listOf<TraceEvent>(
                TraceEvent.NetGap(Duration.ZERO, dropped = 1),
                TraceEvent.NetGap(1_500_000_000L.nanoseconds, dropped = 36),
                TraceEvent.NetGap(2.seconds, dropped = Long.MAX_VALUE),
            )
        assertEquals(events, TraceEvent.parseAll(events.map { it.toString() }))
    }

    /**
     * The wire shape, pinned: a `v1` line, the same three-token prefix every other event has, and the
     * drop count as its single field. Pinned literally because a fixture committed today has to keep
     * parsing after this codec is next touched.
     */
    @Test
    fun theLineIsV1TimestampNetGapCount() {
        assertEquals("v1 1500000000 NET_GAP 36", TraceEvent.NetGap(1_500_000_000L.nanoseconds, dropped = 36).toString())
    }

    /**
     * A gap is replayable input, not an observation — it is *how the platform behaved*, and replay has
     * to reproduce it. [TraceEvent.isInput]'s exhaustive `when` is what forced this decision to be made
     * rather than defaulted into.
     */
    @Test
    fun netGapIsAnInputEvent() {
        assertTrue(TraceEvent.NetGap(Duration.ZERO, dropped = 4).isInput)
    }

    /** A gap line among the events it belongs with, through text, in order. */
    @Test
    fun aWholeGappedTraceSurvivesTheTextBoundary() {
        val trace =
            listOf<TraceEvent>(
                TraceEvent.NetCapability(
                    Duration.ZERO,
                    MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet),
                ),
                TraceEvent.Net(Duration.ZERO, NetworkState.Routable(wifi, InternetAccess.Observed.Confirmed)),
                TraceEvent.DgramOut(1_000L.nanoseconds, len = 3, path = null, payloadHex = "aabbcc"),
                TraceEvent.NetGap(1.seconds, dropped = 12),
                TraceEvent.Net(1.seconds, NetworkState.Offline),
            )
        assertEquals(trace, TraceEvent.parseAll(trace.joinToString("\n")))
    }

    /** A malformed count is a malformed line, not a silently-zero gap. */
    @Test
    fun aNonNumericCountIsRejected() {
        assertFailsWith<IllegalArgumentException> { TraceEvent.parse("v1 0 NET_GAP lots") }
    }
}
