package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.ditchoom.socket.transport.NetworkKind
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import android.net.NetworkCapabilities as AndroidNetworkCapabilities

/**
 * [RadioNetworkRequest] against Robolectric's `ConnectivityManager`: one registration for the radio it
 * names, the network the platform produces, and a close that withdraws it and stays closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class RadioNetworkRequestTests {
    private val connectivityManager: ConnectivityManager
        get() = RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Before
    @After
    fun clearCapturedContext() {
        NetworkMonitor.resetAndroidContextForTesting()
    }

    @Test
    fun eachRadioRequestsItsOwnTransportWithInternet() {
        for ((radio, transport) in listOf(
            NetworkKind.Wifi to AndroidNetworkCapabilities.TRANSPORT_WIFI,
            NetworkKind.Cellular to AndroidNetworkCapabilities.TRANSPORT_CELLULAR,
        )) {
            val before = shadowOf(connectivityManager).networkCallbacks.toSet()
            val request = RadioNetworkRequest(radio, connectivityManager).also { it.register() }
            assertEquals(listOf(transport), TRANSPORTS.filter { request.request.hasTransport(it) }, "$radio")
            assertEquals(true, request.request.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET), "$radio")
            val added = shadowOf(connectivityManager).networkCallbacks - before
            assertEquals(1, added.size, "$radio: one request must be registered")
            request.close()
            assertEquals(before, shadowOf(connectivityManager).networkCallbacks, "$radio: the request must be withdrawn")
        }
    }

    @Test
    fun theNetworkThePlatformProducesIsAttachedUntilLostAndNothingRevivesAClosedRequest() {
        val before = shadowOf(connectivityManager).networkCallbacks.toSet()
        val request = RadioNetworkRequest(NetworkKind.Wifi, connectivityManager).also { it.register() }
        val callback = (shadowOf(connectivityManager).networkCallbacks - before).single()
        assertSame(RadioNetworkState.Requesting, request.state.value)

        val network = ShadowNetwork.newInstance(42)
        callback.onAvailable(network)
        assertEquals(network, assertIs<RadioNetworkState.Attached>(request.state.value).network)

        callback.onLost(network)
        assertSame(RadioNetworkState.Requesting, request.state.value)

        request.close()
        callback.onAvailable(network)
        assertSame(RadioNetworkState.Closed, request.state.value)
        request.close()
        assertEquals(before, shadowOf(connectivityManager).networkCallbacks)
    }

    @Test
    fun withoutACapturedContextTheRequestIsRefusedVisibly() {
        val request = RadioNetworkRequest.open(NetworkKind.Cellular)
        val refused = assertIs<RadioNetworkState.Refused>(request.state.value)
        assertSame(RadioRequestRefusal.NoApplicationContext, refused.reason)
        request.close()
        assertSame(RadioNetworkState.Closed, request.state.value)
    }

    private companion object {
        val TRANSPORTS = listOf(AndroidNetworkCapabilities.TRANSPORT_WIFI, AndroidNetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
