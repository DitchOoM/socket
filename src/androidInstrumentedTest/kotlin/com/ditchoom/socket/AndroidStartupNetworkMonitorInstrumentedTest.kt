package com.ditchoom.socket

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.socket.transport.NetworkId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val monitor = NetworkMonitor.default()
        try {
            val state = monitor.state.value
            assertTrue(
                "a constructed AndroidNetworkMonitor must seed a resolved state, was $state",
                state != NetworkState.Unknown,
            )
            assertTrue(
                "the emulator has a working network, so the seeded state must carry a link, was $state",
                state is NetworkState.Up,
            )
            // The emulator's link is Wi-Fi or Ethernet depending on image/AVD config, so assert the
            // identity resolved at all rather than pinning a kind.
            assertNotNull(state.networkId as NetworkId?)
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
