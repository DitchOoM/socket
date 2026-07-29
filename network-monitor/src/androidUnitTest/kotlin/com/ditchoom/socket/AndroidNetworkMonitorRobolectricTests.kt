package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Host-JVM coverage of the Android monitor against the real framework classes.
 *
 * The emulator lane proves the manifest actually merges and `InitializationProvider.onCreate` actually
 * runs — neither of which exists here. Everything *else* is cheaper and more thorough on Robolectric:
 * the callback state machine, the App Startup capture path and the no-context fallback are all either
 * single-point or unreachable on a single API-29 AVD.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkMonitorRobolectricTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Before
    fun clearCapturedContext() {
        // Installs are one-way in production by design (install once at startup). These are the
        // documented test-only escapes — the same ones downstream consumers use, so this suite
        // exercises the seam it ships rather than reaching past it.
        NetworkMonitor.resetAndroidContextForTesting()
        NetworkMonitor.resetProcessDefaultForTesting()
    }

    // --- App Startup capture path ------------------------------------------------------------

    @Test
    fun initializerCapturesTheApplicationContextSoDefaultBecomesReactive() {
        assertNull(
            NetworkMonitor.androidOrNull(),
            "precondition: with no captured Context there is no monitor to build",
        )

        NetworkMonitorInitializer().create(context)

        val monitor = assertNotNull(NetworkMonitor.androidOrNull(), "the initializer must make a monitor buildable")
        try {
            assertIs<AndroidNetworkMonitor>(monitor)
            assertEquals(MonitorMechanism.PlatformSignalled, monitor.mechanism)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun initializerStoresTheApplicationContextNotTheOneItWasHanded() {
        // Guards the Activity-leak contract: whatever Context App Startup passes, the process-lifetime
        // one is what gets retained. A leaked Activity here would outlive every screen in the app.
        assertSame(context.applicationContext, NetworkMonitorInitializer().create(context))
    }

    @Test
    fun initializerDeclaresNoDependenciesSoItCannotDeadlockStartup() {
        assertTrue(NetworkMonitorInitializer().dependencies().isEmpty())
    }

    @Test
    fun initializerRegistersNoCallbackItOnlyCapturesTheContext() {
        // The whole reason the initializer is capture-only: an app that merely links this library must
        // not pay a ConnectivityManager.NetworkCallback registration at process start. If this ever
        // regresses to eager construction, every consuming app silently gains startup work and wakeups.
        val before = registeredCallbacks().size

        NetworkMonitorInitializer().create(context)

        assertEquals(
            before,
            registeredCallbacks().size,
            "NetworkMonitorInitializer must not register a NetworkCallback; it only stores the Context",
        )
    }

    @Test
    fun buildingTheMonitorIsWhatRegistersTheCallbackAndCloseUnregistersIt() {
        val before = registeredCallbacks().size
        val monitor = NetworkMonitor.android(context)
        assertEquals(
            before + 1,
            registeredCallbacks().size,
            "constructing the monitor is the point at which the callback is registered",
        )

        monitor.close()
        assertEquals(
            before,
            registeredCallbacks().size,
            "close() must unregister — a leaked callback keeps the process awake",
        )
    }

    @Test
    fun installAndroidContextAlsoFeedsTheCaptureSoALaterDefaultStaysReactive() {
        NetworkMonitor.installAndroidContext(context)

        val eager = assertNotNull(NetworkMonitor.installedProcessDefaultOrNull())
        try {
            assertIs<AndroidNetworkMonitor>(eager)
            // The regression this guards: installAndroidContext could install a monitor without
            // recording the Context, leaving a subsequent default() degraded to the fallback.
            assertNotNull(NetworkMonitor.androidOrNull()).close()
        } finally {
            eager.close()
            NetworkMonitor.installProcessDefault(NetworkMonitor.AlwaysAvailable)
        }
    }

    // --- Callback state machine --------------------------------------------------------------

    @Test
    fun onCapabilitiesChangedPublishesAvailabilityAndATypedLinkIdentity() {
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(NET_ID), wifi())

            assertEquals(NetworkAvailability.AVAILABLE, monitor.availability.value)
            val id = monitor.networkId.value
            assertIs<NetworkId.Link>(id)
            assertEquals(NetworkKind.Wifi, id.kind)
        }
    }

    @Test
    fun capabilitiesWithoutInternetReportUnavailable() {
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(
                ShadowNetwork.newInstance(NET_ID),
                capabilities(NetworkCapabilities.TRANSPORT_WIFI, internet = false),
            )
            assertEquals(NetworkAvailability.UNAVAILABLE, monitor.availability.value)
        }
    }

    @Test
    fun aStaleOnLostCannotClearANewerNetwork() {
        // The race the `network == currentNetwork` guard exists for: Android delivers onLost for the old
        // network *after* onAvailable for the new one during a Wi-Fi→cellular handoff. Without the
        // guard the monitor reports UNAVAILABLE while a perfectly good network is up — and QUIC
        // auto-migration would tear down a connection that had just migrated correctly.
        withMonitor { monitor, callback ->
            val old = ShadowNetwork.newInstance(NET_ID)
            val new = ShadowNetwork.newInstance(NET_ID + 1)

            callback.onCapabilitiesChanged(old, wifi())
            callback.onCapabilitiesChanged(new, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
            callback.onLost(old)

            assertEquals(
                NetworkAvailability.AVAILABLE,
                monitor.availability.value,
                "a late onLost for the superseded network must not clear the current one",
            )
            val id = monitor.networkId.value
            assertIs<NetworkId.Link>(id)
            assertEquals(NetworkKind.Cellular, id.kind)
        }
    }

    @Test
    fun onLostForTheCurrentNetworkClearsAvailabilityAndIdentity() {
        withMonitor { monitor, callback ->
            val network = ShadowNetwork.newInstance(NET_ID)
            callback.onCapabilitiesChanged(network, wifi())
            callback.onLost(network)

            assertEquals(NetworkAvailability.UNAVAILABLE, monitor.availability.value)
            assertEquals(NetworkId.Unidentified, monitor.networkId.value)
        }
    }

    @Test
    fun withNoCapturedContextThereIsNoAndroidMonitor() {
        // The precondition for socket's Android default() falling back to PollingNetworkMonitor.
        assertNull(NetworkMonitor.androidOrNull())
    }

    // --- side-effect-free configuration-time query --------------------------------------------

    @Test
    fun hasAndroidApplicationContextTracksTheCapture() {
        assertFalse(NetworkMonitor.hasAndroidApplicationContext(), "nothing captured yet")

        NetworkMonitorInitializer().create(context)
        assertTrue(NetworkMonitor.hasAndroidApplicationContext(), "the initializer captured a Context")

        NetworkMonitor.resetAndroidContextForTesting()
        assertFalse(NetworkMonitor.hasAndroidApplicationContext(), "the reset seam must actually clear it")
    }

    @Test
    fun hasAndroidApplicationContextRegistersNoCallback() {
        // The entire reason this accessor exists. A consumer deciding at configuration time whether it
        // can honour a reactive policy (../webrtc's IceRestartPolicy.OnNetworkChange) must be able to
        // ask without building a monitor: androidOrNull()/default() would register a
        // ConnectivityManager callback just to be read, and processDefault() would additionally cache a
        // PollingNetworkMonitor — and its 5s coroutine — for the life of the process in the negative
        // case. Answering must cost nothing either way.
        NetworkMonitorInitializer().create(context)
        val before = registeredCallbacks().size

        assertTrue(NetworkMonitor.hasAndroidApplicationContext())
        NetworkMonitor.resetAndroidContextForTesting()
        assertFalse(NetworkMonitor.hasAndroidApplicationContext())

        assertEquals(
            before,
            registeredCallbacks().size,
            "querying for a captured Context must never register a NetworkCallback",
        )
    }

    @Test
    fun hasAndroidApplicationContextAgreesWithAndroidOrNull() {
        // The two must never disagree: a consumer that branches on the cheap query and then builds via
        // androidOrNull() would otherwise hit a null it was told could not happen.
        assertEquals(NetworkMonitor.hasAndroidApplicationContext(), NetworkMonitor.androidOrNull() != null)

        NetworkMonitorInitializer().create(context)
        val monitor = NetworkMonitor.androidOrNull()
        try {
            assertEquals(NetworkMonitor.hasAndroidApplicationContext(), monitor != null)
        } finally {
            monitor?.close()
        }
    }

    @Test
    fun resetProcessDefaultForTestingClearsTheInstalledOverride() {
        NetworkMonitor.installProcessDefault(NetworkMonitor.AlwaysAvailable)
        assertNotNull(NetworkMonitor.installedProcessDefaultOrNull(), "precondition: an override is installed")

        NetworkMonitor.resetProcessDefaultForTesting()

        assertNull(
            NetworkMonitor.installedProcessDefaultOrNull(),
            "without this, whichever test installs first decides what every later test observes",
        )
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun registeredCallbacks(): Set<ConnectivityManager.NetworkCallback> = shadowOf(connectivityManager).networkCallbacks

    /**
     * Builds a monitor and hands the block the callback the framework actually received, so the tests
     * drive the real registration path rather than a test-only accessor on production code.
     */
    private fun withMonitor(block: (NetworkMonitor, ConnectivityManager.NetworkCallback) -> Unit) {
        val before = registeredCallbacks().toSet()
        val monitor = NetworkMonitor.android(context)
        try {
            val callback =
                (registeredCallbacks() - before).singleOrNull()
                    ?: error("expected exactly one newly registered NetworkCallback")
            block(monitor, callback)
        } finally {
            monitor.close()
        }
    }

    private fun wifi(): NetworkCapabilities = capabilities(NetworkCapabilities.TRANSPORT_WIFI)

    private fun capabilities(
        transport: Int,
        internet: Boolean = true,
    ): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(transport)
            if (internet) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private companion object {
        const val NET_ID = 100
    }
}
