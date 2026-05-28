package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.NetworkCapabilities as AndroidNetworkCapabilities

/**
 * Android [NetworkMonitor] backed by [ConnectivityManager.NetworkCallback].
 *
 * Event-driven: the OS calls back immediately on network availability changes.
 * Requires `ACCESS_NETWORK_STATE` permission.
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _availability.value = NetworkAvailability.AVAILABLE
            }

            override fun onLost(network: Network) {
                _availability.value = NetworkAvailability.UNAVAILABLE
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: AndroidNetworkCapabilities,
            ) {
                _availability.value =
                    if (caps.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        NetworkAvailability.AVAILABLE
                    } else {
                        NetworkAvailability.UNAVAILABLE
                    }
            }
        }

    init {
        val request =
            NetworkRequest
                .Builder()
                .addCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        connectivityManager.registerNetworkCallback(request, callback)

        // Seed initial state
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        _availability.value =
            if (caps?.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                NetworkAvailability.AVAILABLE
            } else {
                NetworkAvailability.UNAVAILABLE
            }
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}

/**
 * Creates an Android [NetworkMonitor] backed by [ConnectivityManager].
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
fun NetworkMonitor.Companion.android(context: Context): NetworkMonitor = AndroidNetworkMonitor(context)
