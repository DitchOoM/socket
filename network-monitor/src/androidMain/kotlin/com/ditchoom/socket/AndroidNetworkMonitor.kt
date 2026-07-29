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

    /**
     * Whether this monitor tracks the process's **default** network rather than every network matching
     * an INTERNET request.
     *
     * The floor is [O][Build.VERSION_CODES.O], not [N][Build.VERSION_CODES.N] where
     * `registerDefaultNetworkCallback` was introduced, because O is where the platform *documents* that
     * `onAvailable` "will always immediately be followed by a call to `onCapabilitiesChanged`". This
     * class relies on that ordering to publish availability and identity together, and relying on it
     * below the API level that guarantees it would be relying on an implementation detail.
     */
    private val tracksDefaultNetwork = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /**
     * The network currently reflected in the flows, so a stale
     * [onLost][ConnectivityManager.NetworkCallback.onLost] can't clear a newer one.
     *
     * Only used on the pre-O request-based path — the default-network callback needs no such guard (see
     * [onLost][ConnectivityManager.NetworkCallback.onLost] below). Written from the framework's internal
     * callback Handler and read on the same, but also written by the constructor on the caller's thread,
     * so it is [Volatile] rather than a plain field.
     */
    @Volatile
    private var currentNetwork: Network? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Deliberately does NOT call getNetworkCapabilities(network). The platform javadoc:
                // "Do NOT call getNetworkCapabilities(Network) ... in this callback as this is prone to
                // race conditions ... may return an outdated or even a null object. Instead, wait for a
                // call to onCapabilitiesChanged ... whose arguments are guaranteed to be well-ordered."
                // A null there used to degrade the identity to Unidentified while availability said
                // AVAILABLE.
                if (tracksDefaultNetwork) {
                    // onCapabilitiesChanged is guaranteed to follow immediately on O+, and it publishes
                    // availability and identity together. Doing it here too would open a window where a
                    // consumer sees the new network's availability beside the old network's identity.
                    return
                }
                currentNetwork = network
                _availability.value = NetworkAvailability.AVAILABLE
                update(network, connectivityManager.getNetworkCapabilities(network))
            }

            override fun onLost(network: Network) {
                if (tracksDefaultNetwork) {
                    // For registerDefaultNetworkCallback the platform "will only be invoked against the
                    // last network returned by onAvailable() when that network is lost AND no other
                    // network satisfies the criteria of the request" — so there is genuinely nothing
                    // left to fall back to and no stale-onLost race to guard against. A handover to a
                    // better network arrives as onAvailable/onCapabilitiesChanged instead, never here.
                    clear()
                    return
                }
                if (network == currentNetwork) clear()
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

    private fun clear() {
        currentNetwork = null
        _availability.value = NetworkAvailability.UNAVAILABLE
        _networkId.value = NetworkId.Unidentified
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
        // Seed BEFORE registering, not after. Callbacks arrive on the framework's own internal Handler
        // ("The callback is invoked on the default internal Handler"), not this thread, so a seed that
        // ran afterwards could overwrite a fresher callback value with a staler synchronous read.
        // Seeding first makes the callback authoritative: registration immediately replays the current
        // network, so anything this got wrong is corrected within milliseconds.
        seedInitialState()

        try {
            if (tracksDefaultNetwork) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request =
                    NetworkRequest
                        .Builder()
                        .addCapability(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE was stripped from the merged manifest. Without it the callback is
            // registered-but-never-invoked: availability would sit at UNKNOWN forever while `mechanism`
            // claimed PlatformSignalled. Fail loudly instead — this is the one Android case that is dead
            // rather than degraded, and a caller who wants to survive it can fall back to
            // PollingNetworkMonitor, which needs no permission.
            throw NetworkMonitorPermissionException(cause = e)
        }
    }

    /**
     * Resolves availability and identity synchronously so the constructor returns with a real state
     * rather than [NetworkAvailability.UNKNOWN]. This is the one place a synchronous
     * `getNetworkCapabilities` is correct: it runs on the constructing thread before any callback is
     * registered, not inside a callback.
     */
    private fun seedInitialState() {
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
