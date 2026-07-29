package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.os.Build
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins *which* `ConnectivityManager` registration the monitor uses, because that choice — not the
 * callback body — is what fixes the VPN-teardown bug.
 *
 * The bug: with `registerNetworkCallback(INTERNET request)` the platform calls `onLost` "for each
 * network which no longer satisfies the criteria of the callback". A VPN carries
 * NET_CAPABILITY_INTERNET, so it became the tracked network; when it dropped, `onLost` fired and the
 * monitor published UNAVAILABLE — while the Wi-Fi underneath was still up and, being unchanged, sent no
 * callback of its own. Every VPN disconnect made the monitor claim the device was offline.
 *
 * With `registerDefaultNetworkCallback` the platform instead guarantees `onLost` is "only invoked
 * against the last network returned by onAvailable() when that network is lost **and no other network
 * satisfies the criteria of the request**" — the fallback to Wi-Fi arrives as `onAvailable` instead. So
 * asserting the registration mode is asserting the fix; a state-machine test cannot, because both
 * designs handle the default-network event sequence identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RecordingConnectivityManager::class])
class AndroidNetworkMonitorRegistrationTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun onOPlusTheMonitorTracksTheDefaultNetworkNotEveryInternetNetwork() {
        RecordingConnectivityManager.reset()

        val monitor = NetworkMonitor.android(context)
        try {
            assertEquals(
                1,
                RecordingConnectivityManager.defaultCallbackRegistrations,
                "on O+ the monitor must registerDefaultNetworkCallback — this is what stops a VPN " +
                    "teardown from stranding it at UNAVAILABLE over a live Wi-Fi link",
            )
            assertEquals(
                0,
                RecordingConnectivityManager.requestRegistrations,
                "registering an INTERNET NetworkRequest on O+ reintroduces the VPN-teardown bug",
            )
        } finally {
            monitor.close()
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N_MR1])
    fun belowOTheMonitorFallsBackToAnInternetRequest() {
        // registerDefaultNetworkCallback exists from N, but only O documents that onAvailable is
        // "always immediately followed by a call to onCapabilitiesChanged" — the ordering this monitor
        // relies on to publish availability and identity together. Below O it uses the request path.
        RecordingConnectivityManager.reset()

        val monitor = NetworkMonitor.android(context)
        try {
            assertEquals(0, RecordingConnectivityManager.defaultCallbackRegistrations)
            assertEquals(1, RecordingConnectivityManager.requestRegistrations)
            val request = assertNotNull(RecordingConnectivityManager.lastRequest)
            // NetworkRequest.hasCapability is API 30+, so it does not exist on the API level this test
            // simulates; toString() is the only way to inspect the request here. Matching on the
            // platform's own capability name, not a string we invented.
            assertTrue(
                request.toString().contains("INTERNET"),
                "the fallback request must still require INTERNET, was: $request",
            )
        } finally {
            monitor.close()
        }
    }
}

/** Records which registration entry point [AndroidNetworkMonitor] actually calls. */
@Implements(ConnectivityManager::class)
class RecordingConnectivityManager : ShadowConnectivityManager() {
    @Implementation
    override fun registerNetworkCallback(
        request: NetworkRequest?,
        networkCallback: ConnectivityManager.NetworkCallback?,
    ) {
        requestRegistrations++
        lastRequest = request
        super.registerNetworkCallback(request, networkCallback)
    }

    @Implementation
    override fun registerDefaultNetworkCallback(networkCallback: ConnectivityManager.NetworkCallback?) {
        defaultCallbackRegistrations++
        super.registerDefaultNetworkCallback(networkCallback)
    }

    companion object {
        var requestRegistrations = 0
        var defaultCallbackRegistrations = 0
        var lastRequest: NetworkRequest? = null

        fun reset() {
            requestRegistrations = 0
            defaultCallbackRegistrations = 0
            lastRequest = null
        }
    }
}
