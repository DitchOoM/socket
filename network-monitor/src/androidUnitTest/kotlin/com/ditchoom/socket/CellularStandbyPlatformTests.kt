package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CellularStandby] against Robolectric's `ConnectivityManager`: the request it registers, that the
 * registration is really withdrawn, and the process default's refusal when no `Context` was captured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class CellularStandbyPlatformTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Before
    @After
    fun clearCapturedContext() {
        NetworkMonitor.resetAndroidContextForTesting()
    }

    @Test
    fun theRequestAsksForCellularWithInternet() {
        val request = cellularStandbyRequest()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(!request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    @Test
    fun aHoldRegistersWithTheConnectivityManagerAndItsReleaseUnregisters() {
        val before = shadowOf(connectivityManager).networkCallbacks.toSet()
        val standby = CellularStandby(ConnectivityManagerRegistrar(connectivityManager))

        val hold = standby.hold()
        val added = shadowOf(connectivityManager).networkCallbacks - before
        assertEquals(1, added.size, "one request must be registered")

        hold.close()
        assertEquals(before, shadowOf(connectivityManager).networkCallbacks, "the request must be withdrawn")
    }

    @Test
    fun withoutACapturedContextTheProcessDefaultRefusesVisibly() {
        val hold = CellularStandby.processDefault().hold()
        try {
            val refused = assertIs<CellularStandbyState.Refused>(hold.state.value)
            assertSame(CellularStandbyRefusal.NoApplicationContext, refused.reason)
        } finally {
            hold.close()
        }
        assertSame(CellularStandbyState.Released, CellularStandby.processDefault().state.value)
    }
}

/** A stripped `CHANGE_NETWORK_STATE` refuses the request as a typed state, not an exception. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], shadows = [RequestDeniedConnectivityManager::class])
class CellularStandbyPermissionTests {
    @Test
    fun aMissingPermissionIsATypedRefusalCarryingThePlatformsException() {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val standby = CellularStandby(ConnectivityManagerRegistrar(connectivityManager))

        val hold = standby.hold()
        val refused = assertIs<CellularStandbyState.Refused>(standby.state.value)
        val reason = assertIs<CellularStandbyRefusal.MissingPermission>(refused.reason)
        assertTrue(
            reason.cause.message
                .orEmpty()
                .contains("CHANGE_NETWORK_STATE"),
        )

        hold.close()
        assertSame(CellularStandbyState.Released, standby.state.value)
    }
}

/** `requestNetwork` failing the way the platform does without `CHANGE_NETWORK_STATE`. */
@Implements(ConnectivityManager::class)
class RequestDeniedConnectivityManager : ShadowConnectivityManager() {
    @Implementation
    override fun requestNetwork(
        request: NetworkRequest?,
        networkCallback: ConnectivityManager.NetworkCallback?,
    ): Unit = throw SecurityException("com.example does not have android.permission.CHANGE_NETWORK_STATE.")
}
