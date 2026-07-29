package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The stripped-`ACCESS_NETWORK_STATE` path — the one Android case that is *dead* rather than degraded.
 *
 * Unreachable on the emulator lane, which always runs with the permission the merged manifest grants,
 * so a shadow that makes `registerNetworkCallback` throw is the only way to exercise it. Isolated in
 * its own class because the shadow is installed for the whole class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PermissionDeniedConnectivityManager::class])
class AndroidNetworkMonitorPermissionTests {
    @Test
    fun strippedPermissionThrowsTypedNotRawSecurityException() {
        val context: Context = RuntimeEnvironment.getApplication()

        val failure = assertFailsWith<NetworkMonitorPermissionException> { NetworkMonitor.android(context) }

        // Typed, not a bare SecurityException escaping from a framework call: a caller that wants to
        // survive this (falling back to PollingNetworkMonitor, which needs no permission) must be able
        // to catch precisely this and nothing else.
        assertIs<SecurityException>(failure.cause, "the original SecurityException must be preserved as the cause")
        assertTrue(
            failure.message.orEmpty().contains("ACCESS_NETWORK_STATE"),
            "the message must name the permission so the fix is obvious: ${failure.message}",
        )
    }
}

/**
 * A `ConnectivityManager` whose registration calls fail the way the real one does when the app lacks
 * `ACCESS_NETWORK_STATE`. Robolectric's stock shadow always succeeds, so without this the catch block in
 * [AndroidNetworkMonitor]'s init is dead code as far as any test is concerned.
 *
 * **Both** entry points are stubbed, because which one the monitor calls depends on the API level:
 * `registerDefaultNetworkCallback` on O+, `registerNetworkCallback` below it. Stubbing only one would
 * make this suite silently stop exercising the permission path on the other — which is exactly what
 * happened when the monitor moved to the default-network callback.
 */
@Implements(ConnectivityManager::class)
class PermissionDeniedConnectivityManager : ShadowConnectivityManager() {
    @Implementation
    override fun registerNetworkCallback(
        request: NetworkRequest?,
        networkCallback: ConnectivityManager.NetworkCallback?,
    ): Unit = throw permissionDenied()

    @Implementation
    override fun registerDefaultNetworkCallback(networkCallback: ConnectivityManager.NetworkCallback?): Unit = throw permissionDenied()

    private fun permissionDenied() =
        SecurityException(
            "ConnectivityService: Neither user 10001 nor current process has " +
                "android.permission.ACCESS_NETWORK_STATE.",
        )
}
