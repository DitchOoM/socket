package com.ditchoom.socket

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared request's lifecycle, against a recording [StandbyRegistrar]: one registration however many
 * holders, released with the last one, never leaked by a release racing the registration, and a
 * refusal that stays visible while it is held.
 *
 * Robolectric only because `ConnectivityManager.NetworkCallback` and `NetworkCapabilities` are framework
 * classes; the platform is not consulted here. [CellularStandbyPlatformTests] covers the real
 * `ConnectivityManager` calls.
 */
@RunWith(RobolectricTestRunner::class)
class CellularStandbyTests {
    private class RecordingRegistrar(
        private val refuse: CellularStandbyRefusal? = null,
    ) : StandbyRegistrar {
        val registered = mutableListOf<ConnectivityManager.NetworkCallback>()
        val unregistered = mutableListOf<ConnectivityManager.NetworkCallback>()

        /** Runs inside [register], before it returns: the window a concurrent release can land in. */
        var duringRegister: () -> Unit = {}

        override fun register(callback: ConnectivityManager.NetworkCallback): StandbyRegistrar.Outcome {
            duringRegister()
            return when (refuse) {
                null -> {
                    registered += callback
                    StandbyRegistrar.Outcome.Registered
                }
                else -> StandbyRegistrar.Outcome.Refused(refuse)
            }
        }

        override fun unregister(callback: ConnectivityManager.NetworkCallback) {
            assertTrue(callback in registered, "unregistered a callback that was never registered")
            unregistered += callback
        }
    }

    @Test
    fun theFirstHoldRegistersOneRequestAndTheLastReleaseUnregistersIt() {
        val registrar = RecordingRegistrar()
        val standby = CellularStandby(registrar)
        assertSame(CellularStandbyState.Released, standby.state.value)

        val first = standby.hold()
        val second = standby.hold()
        assertEquals(1, registrar.registered.size, "two holders must share one request")
        assertEquals(2, assertIs<CellularStandbyState.Requesting>(standby.state.value).holders)

        first.close()
        assertEquals(0, registrar.unregistered.size, "a remaining holder keeps the request")
        assertEquals(1, assertIs<CellularStandbyState.Requesting>(standby.state.value).holders)

        second.close()
        assertEquals(registrar.registered, registrar.unregistered, "the last release must unregister the request")
        assertSame(CellularStandbyState.Released, standby.state.value)
    }

    @Test
    fun closingOneHoldTwiceReleasesItOnce() {
        val registrar = RecordingRegistrar()
        val standby = CellularStandby(registrar)
        val kept = standby.hold()
        val closedTwice = standby.hold()

        closedTwice.close()
        closedTwice.close()

        assertEquals(1, assertIs<CellularStandbyState.Requesting>(standby.state.value).holders)
        assertEquals(0, registrar.unregistered.size, "a second close must not release the other holder's claim")
        kept.close()
        assertSame(CellularStandbyState.Released, standby.state.value)
    }

    @Test
    fun aHoldAfterTheLastReleaseRegistersAFreshRequest() {
        val registrar = RecordingRegistrar()
        val standby = CellularStandby(registrar)
        standby.hold().close()
        standby.hold()

        assertEquals(2, registrar.registered.size)
        assertNotSame(registrar.registered[0], registrar.registered[1], "a retired callback must never be registered again")
    }

    @Test
    fun aReleaseThatLandsWhileTheRequestIsBeingRegisteredStillUnregistersIt() {
        val registrar = RecordingRegistrar()
        val standby = CellularStandby(registrar)
        // The platform call has not returned when the only holder releases: the release sees a
        // registration that is not registered yet and cannot unregister it itself.
        registrar.duringRegister = {
            registrar.duringRegister = {}
            assertIs<CellularStandbyState.Requesting>(standby.state.value)
            standby.release()
        }
        standby.hold()

        assertSame(CellularStandbyState.Released, standby.state.value)
        assertEquals(1, registrar.registered.size)
        assertEquals(registrar.registered, registrar.unregistered, "the request outlived its last holder")
    }

    @Test
    fun aRefusalIsHeldAsATypedStateAndClearsWithTheLastHolder() {
        val registrar = RecordingRegistrar(refuse = CellularStandbyRefusal.NoApplicationContext)
        val standby = CellularStandby(registrar)

        val first = standby.hold()
        val second = standby.hold()
        val refused = assertIs<CellularStandbyState.Refused>(standby.state.value)
        assertSame(CellularStandbyRefusal.NoApplicationContext, refused.reason)
        assertEquals(2, refused.holders)

        first.close()
        second.close()
        assertSame(CellularStandbyState.Released, standby.state.value)
        assertEquals(0, registrar.unregistered.size, "nothing was registered, so nothing may be unregistered")
    }

    @Test
    fun theNetworkThePlatformProducesIsPublishedAndWithdrawnWhenLost() {
        val registrar = RecordingRegistrar()
        val standby = CellularStandby(registrar)
        standby.hold()
        val callback = registrar.registered.single()
        val network = ShadowNetwork.newInstance(CELL_NET_ID)

        callback.onCapabilitiesChanged(network, cellular(validated = true))
        val attached = assertIs<CellularStandbyState.Attached>(standby.state.value)
        assertSame(network, attached.network)
        val link = assertIs<NetworkState.Routable>(attached.link)
        assertEquals(NetworkKind.Cellular, assertIs<NetworkId.Link>(link.id).kind)
        assertSame(InternetAccess.Observed.Confirmed, link.internet)

        callback.onLost(network)
        assertEquals(1, assertIs<CellularStandbyState.Requesting>(standby.state.value).holders)
    }

    @Test
    fun aCallbackFromARetiredRequestChangesNothing() {
        val registrar = RecordingRegistrar()
        val standby = CellularStandby(registrar)
        standby.hold().close()
        val retired = registrar.registered.single()
        standby.hold()

        retired.onCapabilitiesChanged(ShadowNetwork.newInstance(CELL_NET_ID), cellular(validated = true))

        assertIs<CellularStandbyState.Requesting>(standby.state.value, "a stale callback attached a network nobody requested")
    }

    @Test
    fun concurrentHoldsAndReleasesRegisterAndUnregisterInPairs() {
        val registered = AtomicInteger()
        val unregistered = AtomicInteger()
        val registrar =
            object : StandbyRegistrar {
                override fun register(callback: ConnectivityManager.NetworkCallback): StandbyRegistrar.Outcome {
                    registered.incrementAndGet()
                    return StandbyRegistrar.Outcome.Registered
                }

                override fun unregister(callback: ConnectivityManager.NetworkCallback) {
                    unregistered.incrementAndGet()
                }
            }
        val standby = CellularStandby(registrar)
        val threads = 8
        val rounds = 2_000
        val start = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            thread {
                start.await()
                repeat(rounds) { standby.hold().close() }
                done.countDown()
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "the holders did not finish")

        assertSame(CellularStandbyState.Released, standby.state.value)
        assertEquals(registered.get(), unregistered.get(), "every registered request must be unregistered exactly once")
        assertTrue(registered.get() >= 1)
    }

    private fun cellular(validated: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            }
        }

    private companion object {
        const val CELL_NET_ID = 7
    }
}
