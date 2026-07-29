package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.NetworkCapabilities as AndroidNetworkCapabilities

/**
 * Android [NetworkMonitor] backed by [ConnectivityManager.NetworkCallback].
 *
 * Event-driven: the OS calls back immediately on network availability changes. The same callback's
 * [AndroidNetworkCapabilities] carry the link kind (Wi-Fi / cellular / Ethernet / VPN) and the
 * [Network]'s `networkHandle` carries a stable per-link identity, which feed [networkId] as a typed
 * [NetworkId.Link] — the per-network capability-cache key (RFC_TRANSPORT_FALLBACK §6).
 * Requires `ACCESS_NETWORK_STATE` permission.
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val _networkId = MutableStateFlow<NetworkId>(NetworkId.Unidentified)
    override val networkId: StateFlow<NetworkId> = _networkId.asStateFlow()

    override val mechanism: MonitorMechanism = MonitorMechanism.PlatformSignalled

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The network currently reflected in the flows, so a stale [onLost][ConnectivityManager.NetworkCallback.onLost] can't clear a newer one. */
    private var currentNetwork: Network? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _availability.value = NetworkAvailability.AVAILABLE
                // Capabilities arrive in the onCapabilitiesChanged that immediately follows; identify
                // what we can now so a consumer never sees AVAILABLE with a stale identity.
                update(network, connectivityManager.getNetworkCapabilities(network))
            }

            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    _availability.value = NetworkAvailability.UNAVAILABLE
                    _networkId.value = NetworkId.Unidentified
                }
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
                update(network, caps)
            }
        }

    private fun update(
        network: Network,
        caps: AndroidNetworkCapabilities?,
    ) {
        currentNetwork = network
        _networkId.value =
            androidNetworkId(
                hasWifi = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_WIFI) == true,
                hasCellular = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_CELLULAR) == true,
                hasEthernet = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_ETHERNET) == true,
                hasVpn = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_VPN) == true,
                // networkHandle needs API 23; below that the identity degrades to kind-only.
                handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else null,
            )
    }

    init {
        val request =
            NetworkRequest
                .Builder()
                .addCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE was stripped from the merged manifest. Without it the callback is
            // registered-but-never-invoked: availability would sit at UNKNOWN forever while `mechanism`
            // claimed PlatformSignalled. Fail loudly instead — this is the one Android case that is dead
            // rather than degraded, and a caller who wants to survive it can fall back to
            // PollingNetworkMonitor, which needs no permission.
            throw NetworkMonitorPermissionException(cause = e)
        }

        // Seed initial state
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        _availability.value =
            if (caps?.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                NetworkAvailability.AVAILABLE
            } else {
                NetworkAvailability.UNAVAILABLE
            }
        if (activeNetwork != null) update(activeNetwork, caps)
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}

/**
 * Pure mapper from `NetworkCapabilities` transport bits + `networkHandle` to a typed [NetworkId]
 * (unit-tested without a device). A VPN network's capabilities also list the transport it tunnels
 * over, so `hasVpn` wins and the remaining bits become [NetworkKind.Vpn.transports] — `Vpn(over
 * Wi-Fi)` and `Vpn(over cellular)` are different networks for the cache scope. No recognized
 * transport at all → [NetworkId.Unidentified]; no [handle] (API < 23) → the coarse [NetworkId.KindOnly].
 */
internal fun androidNetworkId(
    hasWifi: Boolean,
    hasCellular: Boolean,
    hasEthernet: Boolean,
    hasVpn: Boolean,
    handle: Long?,
): NetworkId {
    val kind =
        when {
            hasVpn ->
                NetworkKind.Vpn(
                    buildSet {
                        if (hasWifi) add(NetworkKind.Wifi)
                        if (hasCellular) add(NetworkKind.Cellular)
                        if (hasEthernet) add(NetworkKind.Ethernet)
                    },
                )
            hasWifi -> NetworkKind.Wifi
            hasCellular -> NetworkKind.Cellular
            hasEthernet -> NetworkKind.Ethernet
            else -> return NetworkId.Unidentified
        }
    return if (handle != null) NetworkId.Link(kind, handle) else NetworkId.KindOnly(kind)
}

/**
 * Creates an Android [NetworkMonitor] backed by [ConnectivityManager].
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
fun NetworkMonitor.Companion.android(context: Context): NetworkMonitor = AndroidNetworkMonitor(context)

/**
 * Eagerly install a `ConnectivityManager`-backed [NetworkMonitor] as the process default, so QUIC
 * auto-migration (and any other [NetworkMonitor.processDefault] consumer) uses it.
 *
 * ```kotlin
 * NetworkMonitor.installAndroidContext(applicationContext)
 * ```
 *
 * **Normally unnecessary.** [NetworkMonitorInitializer] hands this module the application `Context` via
 * androidx.startup before any app code runs, so the zero-arg `NetworkMonitor.default()` already returns
 * a reactive monitor and `processDefault()` builds one lazily on first use. Call this only to override
 * that with a monitor built from a specific [Context], or when the initializer was removed from the
 * merged manifest.
 *
 * Unlike App Startup's capture-only path, this constructs the monitor **immediately** — it registers a
 * `ConnectivityManager.NetworkCallback` at the moment of the call — and the installed instance is
 * caller-owned: nothing here closes it. Uses [Context.getApplicationContext] to avoid leaking an
 * Activity, and also records that context for [androidOrNull] so a later `default()` stays reactive.
 */
fun NetworkMonitor.Companion.installAndroidContext(context: Context) {
    val application = context.applicationContext
    installAndroidApplicationContext(application)
    installProcessDefault(android(application))
}
