package com.ditchoom.socket.quic.core.time

import com.ditchoom.socket.quic.PinAnswer
import com.ditchoom.socket.quic.QuicheClockPin
import com.ditchoom.socket.quic.QuicheClockPins
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Which loops pin libquiche to their pass's time: only one whose clock is libquiche's, over a backend that can pin it. */
class QuicheTimeTests {
    @Test
    fun aBackendThatPinsOnALibquicheClockIsPinnedAndTheProbeIsReleased() {
        val api = StubQuicheApi()
        val pin = Pin(PinAnswer.Pinned)
        QuicheClockPins.bind(api, pin)
        val time = assertIs<QuicheTime.Pinned>(QuicheTime.of(api, VirtualClock(Nanos(7L), ClockBase.Libquiche)))
        assertSame(pin, time.pin)
        assertEquals(listOf(7L), pin.pins)
        assertEquals(1, pin.unpins, "the probe leaves this thread's clock to libquiche")
    }

    @Test
    fun aVirtualClockAnUnboundApiOrAPlatformThatCannotPinLeaveLibquicheItsOwnClock() {
        val bound = StubQuicheApi().also { QuicheClockPins.bind(it, Pin(PinAnswer.Pinned)) }
        assertSame(QuicheTime.Own, QuicheTime.of(bound, VirtualClock()))
        assertSame(QuicheTime.Own, QuicheTime.of(StubQuicheApi(), VirtualClock(base = ClockBase.Libquiche)))
        val unsupported = StubQuicheApi().also { QuicheClockPins.bind(it, Pin(PinAnswer.Unsupported)) }
        assertSame(QuicheTime.Own, QuicheTime.of(unsupported, VirtualClock(base = ClockBase.Libquiche)))
    }

    /** The loaded backend pins where an `Instant` is a `CLOCK_MONOTONIC` timespec (Linux, Android), and nowhere else. */
    @Test
    fun theLoadedBackendPinsOnLinuxAndAndroidOnly() =
        runBlocking {
            skipOnMissingNativeLib(QuicheTimeTests::class) {
                val os = System.getProperty("os.name").lowercase()
                val time = QuicheTime.of(loadQuicheApi(), MonotonicClock)
                assertEquals(os.contains("linux"), time is QuicheTime.Pinned, "libquiche pinned on $os: $time")
            }
        }

    private class Pin(
        private val answer: PinAnswer,
    ) : QuicheClockPin {
        val pins = ArrayList<Long>()
        var unpins = 0

        override fun pinMonotonic(nanos: Long): PinAnswer {
            pins += nanos
            return answer
        }

        override fun unpin() {
            unpins++
        }
    }
}
