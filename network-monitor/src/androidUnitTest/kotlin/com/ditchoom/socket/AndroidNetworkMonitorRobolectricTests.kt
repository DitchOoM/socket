package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
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
            assertEquals(
                MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet),
                monitor.capability,
            )
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
    fun onCapabilitiesChangedPublishesOneCoherentStateWithATypedLinkIdentity() {
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(NET_ID), wifi())

            // INTERNET without VALIDATED is the ~1s validation window, not "online" — the §1.1 fix.
            val state = assertIs<NetworkState.Routable>(monitor.state.value)
            assertEquals(InternetAccess.Observed.Pending, state.internet)
            assertTrue(state.canRouteOffLink, "the validation window is still worth attempting")
            assertTrue(state.isTransient, "…but a consumer must wait it out rather than tear down")
            assertEquals(NetworkKind.Wifi, assertIs<NetworkId.Link>(state.id).kind)
        }
    }

    @Test
    fun validatedCapabilitiesConfirmReachability() {
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(NET_ID), wifi(validated = true))

            val state = assertIs<NetworkState.Routable>(monitor.state.value)
            assertEquals(InternetAccess.Observed.Confirmed, state.internet)
            assertFalse(state.isTransient, "a validated network has nothing left to resolve")
        }
    }

    @Test
    fun capabilitiesWithoutInternetAreLinkLocalNotOffline() {
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(
                ShadowNetwork.newInstance(NET_ID),
                capabilities(NetworkCapabilities.TRANSPORT_WIFI, internet = false),
            )
            // A link with no INTERNET capability still carries mDNS/multicast — LinkLocal, not Offline,
            // and it keeps its identity so a path-change consumer still sees the right link.
            val state = assertIs<NetworkState.LinkLocal>(monitor.state.value)
            assertFalse(state.canRouteOffLink)
            assertTrue(state.supportsLinkLocal)
            assertEquals(NetworkKind.Wifi, assertIs<NetworkId.Link>(state.id).kind)
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun aSuspendedLinkIsTransientNotOffline() {
        // NOT_SUSPENDED is API 28+ and its ABSENCE is the signal. A suspended cellular link keeps
        // INTERNET and passes nothing; the pre-RFC monitor ignored the bit entirely and reported it as
        // plain "available" (Chromium hit the same thing — crbug.com/1120144).
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(
                ShadowNetwork.newInstance(NET_ID),
                capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, validated = true, notSuspended = false),
            )

            val state = assertIs<NetworkState.Routable>(monitor.state.value)
            assertEquals(InternetAccess.Observed.Blocked(BlockReason.Suspended), state.internet)
            assertTrue(state.isTransient, "a suspended link resolves on its own — wait, do not tear down")
            assertFalse(state.canRouteOffLink)
            assertFalse(state.needsUserAction)
        }
    }

    /**
     * Below API 28 the bit does not exist, and defaulting it to `false` would report every pre-28 device
     * as permanently suspended.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.N_MR1])
    fun beforeApi28TheAbsentNotSuspendedBitIsNotReadAsSuspended() {
        // Pre-O the request-based path only accepts updates for the current default network.
        setActiveNetwork(NET_ID)
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(NET_ID), wifi(validated = true))

            val state = assertIs<NetworkState.Routable>(monitor.state.value)
            assertEquals(InternetAccess.Observed.Confirmed, state.internet)
        }
    }

    @Test
    fun aCaptivePortalNeedsUserActionRatherThanARetry() {
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(NET_ID), portal())

            val state = assertIs<NetworkState.Routable>(monitor.state.value)
            assertEquals(InternetAccess.Observed.Blocked(BlockReason.CaptivePortal), state.internet)
            assertTrue(state.needsUserAction, "retrying a portal-intercepted network is futile")
            assertFalse(state.canRouteOffLink)
            assertFalse(state.isTransient)
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N_MR1])
    fun preOAStaleOnLostCannotClearANewerNetwork() {
        // Below O the monitor registers an INTERNET NetworkRequest, which per the platform javadoc is
        // "called for each network which no longer satisfies the criteria of the callback" — so a late
        // onLost for a superseded network really can arrive after the new one is already current. That
        // is the race the `network == currentNetwork` guard exists for. Without it the monitor reports
        // UNAVAILABLE while a perfectly good network is up, and QUIC auto-migration would tear down a
        // connection that had just migrated correctly. The default network moves with the switch here,
        // as it does on a device — the framework re-routes first, then delivers the stragglers.
        setActiveNetwork(NET_ID)
        withMonitor { monitor, callback ->
            val old = ShadowNetwork.newInstance(NET_ID)
            val new = ShadowNetwork.newInstance(NET_ID + 1)

            callback.onCapabilitiesChanged(old, wifi())
            setActiveNetwork(NET_ID + 1)
            callback.onCapabilitiesChanged(new, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
            callback.onLost(old)

            val state = monitor.state.value
            assertTrue(
                state.canRouteOffLink,
                "a late onLost for the superseded network must not clear the current one, was $state",
            )
            assertEquals(NetworkKind.Cellular, assertIs<NetworkId.Link>(state.networkId).kind)
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun preOConcurrentNetworksDoNotFlapStateOffTheDefaultNetwork() {
        // Below O the INTERNET NetworkRequest matches EVERY satisfying network, so with Wi-Fi
        // associated and cell data enabled — the ordinary phone state — onCapabilitiesChanged
        // interleaves for two live networks on a completely stable device. Unfiltered, state alternated
        // between two NetworkId.Links, and on this branch that flap feeds pathChanges() and QUIC
        // auto-migration. The default network is the tie-break; the concurrent one's chatter must not
        // publish.
        setActiveNetwork(NET_ID)
        withMonitor { monitor, callback ->
            val wifiNetwork = ShadowNetwork.newInstance(NET_ID)
            val cellular = ShadowNetwork.newInstance(NET_ID + 1)

            callback.onCapabilitiesChanged(wifiNetwork, wifi(validated = true))
            val settled = monitor.state.value
            assertEquals(NetworkKind.Wifi, assertIs<NetworkId.Link>(settled.networkId).kind)

            // The concurrent cellular network reports in — repeatedly, as real baseband does — while
            // the default has not moved.
            callback.onCapabilitiesChanged(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
            callback.onCapabilitiesChanged(
                cellular,
                capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, validated = true),
            )

            assertEquals(settled, monitor.state.value, "a non-default network's callback must not flap state")
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun preOAGenuineDefaultSwitchStillPropagates() {
        // The gate must not over-filter: when the default really moves (Wi-Fi out of range), the new
        // default's callback is authoritative and the migration signal must still fire.
        setActiveNetwork(NET_ID)
        withMonitor { monitor, callback ->
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(NET_ID), wifi(validated = true))

            setActiveNetwork(NET_ID + 1)
            callback.onCapabilitiesChanged(
                ShadowNetwork.newInstance(NET_ID + 1),
                capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, validated = true),
            )

            assertEquals(NetworkKind.Cellular, assertIs<NetworkId.Link>(monitor.state.value.networkId).kind)
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun preOANullActiveNetworkStillLandsInPlaceChangesOnTheTrackedNetwork() {
        // getActiveNetwork() is transiently null mid-handoff. The gate falls back to the network
        // already reflected in state, so a capability change on the link we track (e.g. VALIDATED
        // finally landing) is not dropped — while an untracked network still cannot steal the state.
        setActiveNetwork(NET_ID)
        withMonitor { monitor, callback ->
            val wifiNetwork = ShadowNetwork.newInstance(NET_ID)
            val cellular = ShadowNetwork.newInstance(NET_ID + 1)

            callback.onCapabilitiesChanged(wifiNetwork, wifi())
            shadowOf(connectivityManager).setDefaultNetworkActive(false)

            callback.onCapabilitiesChanged(wifiNetwork, wifi(validated = true))
            val state = assertIs<NetworkState.Routable>(monitor.state.value)
            assertEquals(InternetAccess.Observed.Confirmed, state.internet)

            callback.onCapabilitiesChanged(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
            assertEquals(state, monitor.state.value, "an untracked network must not win a null-default window")
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun preOWithNothingTrackedAndNoDefaultTheFirstNetworkIsAccepted() {
        // Cold start with no default yet (airplane mode dropping, first association): there is nothing
        // to be loyal to, so the first network to report is accepted rather than leaving state stuck at
        // Offline. A wrong first pick is corrected by the next callback once the default reappears.
        shadowOf(connectivityManager).setDefaultNetworkActive(false)
        withMonitor { monitor, callback ->
            assertEquals(NetworkState.Offline, monitor.state.value, "seeded from a null active network")

            callback.onCapabilitiesChanged(
                ShadowNetwork.newInstance(NET_ID + 1),
                capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, validated = true),
            )

            assertEquals(NetworkKind.Cellular, assertIs<NetworkId.Link>(monitor.state.value.networkId).kind)
        }
    }

    @Test
    fun onOPlusAHandoverArrivesAsOnAvailableAndOnLostMeansTotalLoss() {
        // On O+ the monitor tracks the *default* network, where the platform guarantees onLost "will
        // only be invoked against the last network returned by onAvailable() when that network is lost
        // and no other network satisfies the criteria of the request". A handover to a better network
        // therefore arrives as onAvailable/onCapabilitiesChanged for the newcomer — never as a stale
        // onLost — so there is no race left to guard, and onLost unambiguously means "nothing left".
        withMonitor { monitor, callback ->
            val wifiNetwork = ShadowNetwork.newInstance(NET_ID)
            val cellular = ShadowNetwork.newInstance(NET_ID + 1)

            callback.onCapabilitiesChanged(wifiNetwork, wifi())
            // Handover: the framework announces the new best network. No onLost for the old one.
            callback.onAvailable(cellular)
            callback.onCapabilitiesChanged(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))

            assertTrue(monitor.state.value.canRouteOffLink)
            assertEquals(NetworkKind.Cellular, assertIs<NetworkId.Link>(monitor.state.value.networkId).kind)

            // Now genuine total loss.
            callback.onLost(cellular)
            assertEquals(NetworkState.Offline, monitor.state.value)
            assertEquals(NetworkId.Unidentified, monitor.state.value.networkId)
        }
    }

    @Test
    fun aVpnDisconnectingOverALiveWifiDoesNotStrandTheMonitorOffline() {
        // Regression test for a real bug found on 2026-07-29. The monitor used to track a single
        // `currentNetwork` against an INTERNET NetworkRequest. A VPN (which carries
        // NET_CAPABILITY_INTERNET) became `currentNetwork`; when it dropped, onLost matched and the
        // monitor published UNAVAILABLE/Unidentified — while the Wi-Fi it tunnelled over was still up
        // and still satisfied the request, sending no callback of its own because nothing about it had
        // changed. Every VPN disconnect made the monitor claim the device was offline.
        //
        // On the default-network callback the platform resolves this for us: the default reverts to
        // Wi-Fi and that arrives as onAvailable/onCapabilitiesChanged, not onLost.
        withMonitor { monitor, callback ->
            val wifiNetwork = ShadowNetwork.newInstance(NET_ID)
            val vpn = ShadowNetwork.newInstance(NET_ID + 1)

            callback.onCapabilitiesChanged(wifiNetwork, wifi())
            callback.onAvailable(vpn)
            // A VPN's capabilities also list the transport it tunnels over — that is what makes the
            // identity Vpn(over Wi-Fi) rather than a bare Vpn.
            callback.onCapabilitiesChanged(
                vpn,
                capabilitiesOver(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI),
            )
            assertIs<NetworkKind.Vpn>(assertIs<NetworkId.Link>(monitor.state.value.networkId).kind)

            // VPN drops; the default reverts to the still-live Wi-Fi.
            callback.onAvailable(wifiNetwork)
            callback.onCapabilitiesChanged(wifiNetwork, wifi())

            assertTrue(
                monitor.state.value.canRouteOffLink,
                "Wi-Fi is still up — the monitor must not report the device offline",
            )
            assertEquals(NetworkKind.Wifi, assertIs<NetworkId.Link>(monitor.state.value.networkId).kind)
        }
    }

    @Test
    fun onLostForTheCurrentNetworkPublishesOfflineWhichCarriesNoIdentity() {
        withMonitor { monitor, callback ->
            val network = ShadowNetwork.newInstance(NET_ID)
            callback.onCapabilitiesChanged(network, wifi())
            callback.onLost(network)

            // One value, so a dead link's identity cannot survive the loss: Offline structurally has none.
            assertEquals(NetworkState.Offline, monitor.state.value)
            assertEquals(NetworkId.Unidentified, monitor.state.value.networkId)
        }
    }

    @Test
    fun everyPublishedStateIsOneTheDeclaredCapabilityPermits() {
        withMonitor { monitor, callback ->
            val network = ShadowNetwork.newInstance(NET_ID)
            val seen = mutableListOf<NetworkState>()

            fun record() = seen.add(monitor.state.value)

            record()
            callback.onCapabilitiesChanged(network, wifi())
            record()
            callback.onCapabilitiesChanged(network, wifi(validated = true))
            record()
            callback.onCapabilitiesChanged(network, portal())
            record()
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, internet = false))
            record()
            callback.onLost(network)
            record()

            seen.forEach {
                assertTrue(
                    monitor.capability.resolution.permits(it),
                    "AndroidNetworkMonitor declares ${monitor.capability.resolution} but published $it",
                )
            }
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
     * Points `getActiveNetwork()` at the network with [netId]. ShadowConnectivityManager derives the
     * active `Network` from the active `NetworkInfo`'s *type* (its `netIdToNetwork` map is keyed on
     * it), so the type doubles as the netId here — the same quirk the shadow's own default
     * (`TYPE_WIFI` → `Network(1)`) relies on. `ShadowNetworkInfo.newInstance` bypasses the real
     * constructor, so an arbitrary netId is not rejected as an invalid legacy type.
     */
    private fun setActiveNetwork(netId: Int) {
        shadowOf(connectivityManager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                netId,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
    }

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

    private fun wifi(validated: Boolean = false): NetworkCapabilities =
        capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = validated)

    /** Wi-Fi behind a captive portal — and also VALIDATED, which some builds really do report. */
    private fun portal(): NetworkCapabilities =
        capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }

    /** Capabilities carrying more than one transport, as a VPN over a physical link does. */
    private fun capabilitiesOver(vararg transports: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            transports.forEach { transport -> shadowOf(it).addTransportType(transport) }
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            }
        }

    /**
     * A capabilities object shaped like a real one. [notSuspended] defaults to `true` because that is
     * what every working Android network reports and its **absence** is the suspended signal — a shadow
     * that omitted it would make every test below see `Blocked(Suspended)`. The bit only exists from
     * API 28, so it is only set where the platform has it (below that the monitor hardcodes `true`).
     */
    private fun capabilities(
        transport: Int,
        internet: Boolean = true,
        validated: Boolean = false,
        notSuspended: Boolean = true,
    ): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(transport)
            if (internet) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (notSuspended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            }
        }

    private companion object {
        const val NET_ID = 100
    }
}
