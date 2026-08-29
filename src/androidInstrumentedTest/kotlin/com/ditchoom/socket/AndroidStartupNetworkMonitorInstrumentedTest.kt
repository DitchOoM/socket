package com.ditchoom.socket

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.socket.transport.NetworkId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the androidx.startup path end to end on a real device: **without any call to
 * `NetworkMonitor.installAndroidContext`**, `NetworkMonitor.default()` is a reactive
 * `ConnectivityManager`-backed monitor.
 *
 * This is the regression test for the failure mode App Startup removes. Previously the Android
 * `default()` was hard-wired to [NetworkMonitor.AlwaysAvailable], and an app that forgot the startup
 * call got a monitor that silently claimed the network was permanently up — no exception, no log, just
 * QUIC auto-migration and the transport-fallback capability cache quietly never firing. Nothing but an
 * on-device assertion can catch a regression here: the whole mechanism is manifest merging plus
 * `InitializationProvider.onCreate`, neither of which exists in a JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStartupNetworkMonitorInstrumentedTest {
    @Test
    fun defaultIsReactiveWithoutAnyManualContextInstall() {
        val monitor = NetworkMonitor.default()
        try {
            assertNotSame(
                "NetworkMonitorInitializer should have supplied the application Context via " +
                    "androidx.startup, so default() must not degrade to the no-op AlwaysAvailable",
                NetworkMonitor.AlwaysAvailable,
                monitor,
            )
            assertTrue(
                "default() should be a ConnectivityManager-backed monitor, was ${monitor::class.java.name}",
                monitor is AndroidNetworkMonitor,
            )
            // The seam ../webrtc branches on for IceRestartPolicy.OnNetworkChange. Asserting the
            // concrete class above is not enough: the capability is what consumers actually read, and it
            // must agree with what was wired. Polled here would mean the initializer never ran, and a
            // resolution below RouteAndInternet would mean the ladder never reached the device. The
            // link-quality axis is API-gated like the monitor itself gates it: getSignalStrength()
            // exists from API 29, so a device at or above it must declare Rssi and below must declare
            // the honest None — mirroring the gate keeps this lane meaningful on every emulator image
            // it runs against rather than pinning one API level's answer.
            assertEquals(
                "a ConnectivityManager-backed monitor must report itself as pushed and fully resolving",
                MonitorCapability(
                    MonitorMechanism.PlatformSignalled,
                    ReachResolution.RouteAndInternet,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        LinkQualityResolution.Rssi
                    } else {
                        LinkQualityResolution.None
                    },
                ),
                monitor.capability,
            )
        } finally {
            monitor.close()
        }
    }

    @Test
    fun defaultReportsTheEmulatorsNetworkState() {
        // The emulator has a working network, so the monitor seeded from ConnectivityManager's
        // activeNetwork must say so — the assertion that it is genuinely wired, not merely constructed.
        //
        // Two different owners can make that seed something other than Up, and a bare "was Offline"
        // cannot tell them apart (#503: twice on API 35 the seed was Offline, and a re-run of the same
        // commit passed). Either the emulator had no default network at that instant — a boot race the
        // seed reported honestly — or the monitor misread one it had. So the emulator half is
        // established FIRST, with its own verdict and before the monitor exists, and the monitor is only
        // ever judged against a platform that has just said "there is a network". A second read right
        // after the seed settles the remaining ambiguity: a network that was there before and is gone
        // after is a transient the seed most likely saw too; one still there means the monitor
        // disagreed with the platform.
        val before = awaitEmulatorNetwork()
        val monitor = NetworkMonitor.default()
        val state = monitor.state.value
        val after = connectivitySnapshot()
        try {
            assertTrue(
                "a constructed AndroidNetworkMonitor must seed a resolved state, was $state; " +
                    "ConnectivityManager before=[$before] after=[$after] — the monitor, not the emulator",
                state != NetworkState.Unknown,
            )
            assertTrue(seedVerdict(before, after, state), state is NetworkState.Up)
            val up = state as NetworkState.Up
            // Identity, not merely "some link": the seed's handle must be the network ConnectivityManager
            // reported around it. Either read is accepted because the default may legitimately move
            // between them; a handle matching neither is a seed taken from something the platform never
            // called the default. The emulator's link is Wi-Fi or Ethernet depending on image/AVD config,
            // so the kind is not pinned.
            val id = up.id
            assertTrue(
                "the seed's identity must be the active network ConnectivityManager reported, was $id; " +
                    "before=[$before] after=[$after] — the monitor, not the emulator",
                id is NetworkId.Link && id.handle in before.handles + after.handles,
            )
            // NET_CAPABILITY_INTERNET was the readiness precondition, and the seed's ladder maps its
            // absence to LinkLocal — so unless the bit dropped between the two reads, the seed must have
            // climbed past that rung. A monitor blind to the bit would sit on LinkLocal for every network.
            if (after.hasInternet) {
                assertTrue(
                    "ConnectivityManager reported NET_CAPABILITY_INTERNET on the active network before " +
                        "and after the seed, so the seed must be Routable, was $state; " +
                        "before=[$before] after=[$after] — the monitor, not the emulator",
                    up is NetworkState.Routable,
                )
            } else {
                Log.w(
                    TAG,
                    "NET_CAPABILITY_INTERNET dropped between the readiness read and the post-seed read, " +
                        "so the Routable rung is not asserted this run: before=[$before] after=[$after]",
                )
            }
            // Whatever rung the emulator lands on, it must be one this monitor's capability permits.
            assertTrue(
                "AndroidNetworkMonitor declares ${monitor.capability.resolution} but published $state",
                monitor.capability.resolution.permits(state),
            )
        } finally {
            monitor.close()
        }
    }
}

private const val TAG = "StartupMonitorTest"

/** How long the emulator gets to have a default network before the run is called an environment failure. */
private const val READINESS_BUDGET_MS = 10_000L
private const val READINESS_POLL_MS = 250L

private val connectivityManager: ConnectivityManager
    get() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(ConnectivityManager::class.java)
    }

/**
 * What `ConnectivityManager` reported at one instant, in the shape every failure message in this file
 * prints, so the next sighting of #503 names its cause instead of a bare state.
 *
 * It reads exactly what [AndroidNetworkMonitor]'s seed reads — `activeNetwork`, then
 * `getNetworkCapabilities(activeNetwork)`, then the transport and capability bits the seed folds — so a
 * seed and a snapshot taken side by side are comparable read for read. On top of that it carries what
 * the seed does not read but a diagnosis needs: the platform's own capabilities dump (every transport
 * and capability bit, at whatever API level is running, without this file having to know their names),
 * the link's interface name, `isDefaultNetworkActive`, and — when there is no default — every network
 * the platform knows of at all, so "nothing has come up yet" and "something is up but nothing was chosen
 * as the default" read differently.
 *
 * Three cases, not one class with nullable fields: which of `activeNetwork` and
 * `getNetworkCapabilities` came back empty is the diagnosis.
 */
private sealed interface ConnectivitySnapshot {
    val isDefaultNetworkActive: Boolean

    /** Handles this read attributed to the default network — empty when there was none. */
    val handles: Set<Long>

    /** `NET_CAPABILITY_INTERNET` on the default network — false when there is no default to carry it. */
    val hasInternet: Boolean

    /** `activeNetwork == null`: a seed taken here reads [NetworkState.Offline], honestly. */
    class NoActiveNetwork(
        override val isDefaultNetworkActive: Boolean,
        /** Every network the platform had, rendered — distinguishes "nothing up" from "none chosen". */
        private val knownNetworks: List<String>,
    ) : ConnectivitySnapshot {
        override val handles: Set<Long> get() = emptySet()
        override val hasInternet: Boolean get() = false

        override fun toString(): String =
            "activeNetwork=null, isDefaultNetworkActive=$isDefaultNetworkActive, " +
                "allNetworks=$knownNetworks"
    }

    /**
     * `activeNetwork` was non-null but `getNetworkCapabilities` on it returned null: the network went
     * away between the two reads. The seed makes the same two reads in the same order, so it can see
     * this too — and folds it as an Up with no capability bits.
     */
    class GoneMidRead(
        private val netId: String,
        private val handle: Long,
        override val isDefaultNetworkActive: Boolean,
    ) : ConnectivitySnapshot {
        override val handles: Set<Long> get() = setOf(handle)
        override val hasInternet: Boolean get() = false

        override fun toString(): String =
            "activeNetwork=netId:$netId handle=$handle, capabilities=<null: network gone between " +
                "activeNetwork and getNetworkCapabilities>, isDefaultNetworkActive=$isDefaultNetworkActive"
    }

    class ActiveNetwork(
        private val netId: String,
        private val handle: Long,
        /** Of the four transports the seed's identity mapping consults, the ones present. */
        private val transports: List<String>,
        override val hasInternet: Boolean,
        private val hasValidated: Boolean,
        private val hasCaptivePortal: Boolean,
        /** Defaulted exactly as the seed defaults it: the bit does not exist below API 28. */
        private val notSuspended: Boolean,
        private val interfaceName: String,
        /** `NetworkCapabilities.toString()` — every bit the platform has, in its own words. */
        private val platformDump: String,
        override val isDefaultNetworkActive: Boolean,
    ) : ConnectivitySnapshot {
        override val handles: Set<Long> get() = setOf(handle)

        override fun toString(): String =
            "activeNetwork=netId:$netId handle=$handle, transports=$transports, INTERNET=$hasInternet, " +
                "VALIDATED=$hasValidated, CAPTIVE_PORTAL=$hasCaptivePortal, NOT_SUSPENDED=$notSuspended, " +
                "interface=$interfaceName, isDefaultNetworkActive=$isDefaultNetworkActive, " +
                "platform=$platformDump"
    }
}

private val monitorTransports =
    listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
        NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
        NetworkCapabilities.TRANSPORT_VPN to "VPN",
    )

private fun connectivitySnapshot(): ConnectivitySnapshot {
    val cm = connectivityManager
    val isDefaultNetworkActive = cm.isDefaultNetworkActive
    val active =
        cm.activeNetwork
            ?: return ConnectivitySnapshot.NoActiveNetwork(isDefaultNetworkActive, cm.knownNetworks())
    val caps =
        cm.getNetworkCapabilities(active)
            ?: return ConnectivitySnapshot.GoneMidRead(active.toString(), active.networkHandle, isDefaultNetworkActive)
    return ConnectivitySnapshot.ActiveNetwork(
        netId = active.toString(),
        handle = active.networkHandle,
        transports = monitorTransports.filter { (bit, _) -> caps.hasTransport(bit) }.map { (_, name) -> name },
        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        hasCaptivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        notSuspended =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            } else {
                true
            },
        interfaceName = cm.interfaceNameOf(active),
        platformDump = caps.toString(),
        isDefaultNetworkActive = isDefaultNetworkActive,
    )
}

// allNetworks is deprecated from API 31 in favour of callbacks — for a monitor. This is a one-shot
// diagnostic read on a test's failure path, where the synchronous "what exists at all" answer is the
// point and no callback could be registered early enough to have seen the seed's instant.
@Suppress("DEPRECATION")
private fun ConnectivityManager.knownNetworks(): List<String> =
    allNetworks.map { network ->
        val caps = getNetworkCapabilities(network) ?: "<null: gone mid-read>"
        "netId:$network handle=${network.networkHandle} caps=$caps"
    }

private fun ConnectivityManager.interfaceNameOf(network: Network): String {
    val properties = getLinkProperties(network) ?: return "<no LinkProperties>"
    return properties.interfaceName ?: "<LinkProperties without an interface name>"
}

/**
 * Waits, bounded, for the emulator to have a default network carrying `NET_CAPABILITY_INTERNET` — the
 * premise "the emulator has a working network" that [AndroidStartupNetworkMonitorInstrumentedTest]
 * rests on. When the budget runs out it fails with an environment verdict, never a monitor one: the
 * monitor has not been constructed yet, so nothing here can be its fault.
 *
 * INTERNET, not VALIDATED: the seed needs only a non-null `activeNetwork` to read Up and only INTERNET
 * to climb to Routable. VALIDATED decides Confirmed against Pending, which the test does not pin — the
 * emulator's NAT validates instantly, an API-35 image has been seen dropping the bit briefly, and
 * neither is what "has a network" means. The bit is still in every snapshot for the record.
 *
 * A network that arrives late is the #503 race caught in the act, so a green run says so on logcat too.
 */
private fun awaitEmulatorNetwork(): ConnectivitySnapshot.ActiveNetwork {
    val started = SystemClock.elapsedRealtime()
    val first = connectivitySnapshot()
    var last = first
    var reads = 1
    while (true) {
        val current = last
        if (current is ConnectivitySnapshot.ActiveNetwork && current.hasInternet) {
            if (reads > 1) {
                Log.w(
                    TAG,
                    "emulator network became ready only after ${SystemClock.elapsedRealtime() - started}ms " +
                        "($reads reads): first=[$first] last=[$current]",
                )
            }
            return current
        }
        if (SystemClock.elapsedRealtime() - started >= READINESS_BUDGET_MS) {
            throw AssertionError(
                "emulator had no active network with NET_CAPABILITY_INTERNET within " +
                    "${READINESS_BUDGET_MS / 1000}s ($reads reads, one every ${READINESS_POLL_MS}ms): " +
                    "first=[$first], last=[$last] — environment, not the monitor",
            )
        }
        SystemClock.sleep(READINESS_POLL_MS)
        last = connectivitySnapshot()
        reads++
    }
}

/**
 * The one message for "the seed is not Up", worded by who owns it. [before] is the readiness read — an
 * active network with INTERNET, by construction — and [after] the read taken right after the seed.
 */
private fun seedVerdict(
    before: ConnectivitySnapshot.ActiveNetwork,
    after: ConnectivitySnapshot,
    state: NetworkState,
): String {
    val owner =
        when (after) {
            is ConnectivitySnapshot.ActiveNetwork ->
                "ConnectivityManager reported an active network both before and after the seed, so the " +
                    "monitor read the platform wrong — the monitor, not the emulator"
            is ConnectivitySnapshot.GoneMidRead, is ConnectivitySnapshot.NoActiveNetwork ->
                "the active network was gone by the read after the seed, so the seed most likely saw the " +
                    "same absence — an emulator transient, not the monitor"
        }
    return "NetworkMonitor.default() seeded $state although the emulator had a network: " +
        "before=[$before], after=[$after]. $owner"
}
