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
 * Event-driven: the OS calls back immediately on network changes. The same callback's
 * [AndroidNetworkCapabilities] carry everything the full ladder needs — the link kind (Wi-Fi / cellular
 * / Ethernet / VPN) and the `networkHandle` for identity, plus `INTERNET` / `VALIDATED` /
 * `CAPTIVE_PORTAL` / `NOT_SUSPENDED` for reachability. Requires
 * `ACCESS_NETWORK_STATE`.
 *
 * This is the one platform that reaches every rung, so its capability is
 * [ReachResolution.RouteAndInternet]. Three defects fixed relative to the pre-RFC monitor, all of them
 * cases where it reported plain "available" and passed no data (RFC_NETWORK_REACHABILITY §1.1):
 *  - **`INTERNET` without `VALIDATED`** is now [InternetAccess.Observed.Pending], not online. Measured on
 *    a Realme RMX3933: real Wi-Fi grants `INTERNET` ~0.7–1s before `VALIDATED`, on 3/3 reassociations.
 *  - **A captive portal** is now [BlockReason.CaptivePortal] — that window never closes on its own, and
 *    retrying is futile.
 *  - **`NOT_SUSPENDED` was ignored entirely.** A suspended cellular link keeps `INTERNET` and passes
 *    nothing; Chromium hit this and fixed it (crbug.com/1120144).
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    override val capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet)

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Whether this monitor tracks the process's **default** network rather than every network matching
     * an INTERNET request.
     *
     * The floor is [O][Build.VERSION_CODES.O], not [N][Build.VERSION_CODES.N] where
     * `registerDefaultNetworkCallback` was introduced, because O is where the platform *documents* that
     * `onAvailable` "will always immediately be followed by a call to `onCapabilitiesChanged`". This
     * class relies on that ordering to publish one coherent state, and relying on it below the API level
     * that guarantees it would be relying on an implementation detail.
     */
    private val tracksDefaultNetwork = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /**
     * The network currently reflected in [state], so a stale
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
                if (tracksDefaultNetwork) {
                    // onCapabilitiesChanged is guaranteed to follow immediately on O+, and it publishes
                    // one coherent NetworkState. Publishing here too would open a window where a consumer
                    // sees the new network's reachability beside the old network's identity — precisely
                    // the torn read collapsing to one value was meant to make impossible.
                    return
                }
                if (!acceptsUpdateFor(network)) return
                currentNetwork = network
                publish(network, connectivityManager.getNetworkCapabilities(network))
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
                if (!acceptsUpdateFor(network)) return
                publish(network, caps)
            }
        }

    /**
     * Whether a callback for [network] may reach [publish]. Only the pre-O request-based path ever says
     * no — see [acceptsNetworkUpdate] for why that path multi-fires and how the gate decides.
     *
     * Reading [ConnectivityManager.activeNetwork] *inside* a callback is deliberate, even though the
     * platform javadoc warns against synchronous `ConnectivityManager` calls in callbacks: the risk
     * a stale read carries is tolerated by construction. A `null` answer falls back to the tracked
     * network ([acceptsNetworkUpdate]'s both-null arm accepts the first comer), and the tracked
     * network is accepted even when a different default is read — so staleness can at worst delay a
     * switch until the next callback, never wedge the gate. The default is also the same authority
     * [seedInitialState] already trusts. Handles stand in for the [Network]s ([Network] equality is
     * its netId, and `networkHandle` is derived from that same netId), keeping the decision itself pure.
     */
    private fun acceptsUpdateFor(network: Network): Boolean {
        if (tracksDefaultNetwork) return true
        return acceptsNetworkUpdate(
            tracksDefaultNetwork = false,
            candidateHandle = network.networkHandle,
            activeHandle = connectivityManager.activeNetwork?.networkHandle,
            trackedHandle = currentNetwork?.networkHandle,
        )
    }

    private fun clear() {
        currentNetwork = null
        _state.value = NetworkState.Offline
    }

    /** Publish one coherent [NetworkState] — identity and reachability from the same capabilities object. */
    private fun publish(
        network: Network,
        caps: AndroidNetworkCapabilities?,
    ) {
        currentNetwork = network
        _state.value =
            androidNetworkState(
                id =
                    androidNetworkId(
                        hasWifi = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_WIFI) == true,
                        hasCellular = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_CELLULAR) == true,
                        hasEthernet = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_ETHERNET) == true,
                        hasVpn = caps?.hasTransport(AndroidNetworkCapabilities.TRANSPORT_VPN) == true,
                        handle = network.networkHandle,
                    ),
                hasInternet = caps.has(AndroidNetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidated = caps.has(AndroidNetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasCaptivePortal = caps.has(AndroidNetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                // NOT_SUSPENDED is API 28+. Below that the bit does not exist, and its ABSENCE is the
                // suspended signal — so it must default to `true` (not suspended), never `false`, or every
                // pre-28 device would report a permanently suspended link.
                notSuspended =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        caps.has(AndroidNetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    } else {
                        true
                    },
            )
    }

    private fun AndroidNetworkCapabilities?.has(capability: Int): Boolean = this?.hasCapability(capability) == true

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
            // registered-but-never-invoked: state would sit at Unknown forever while `capability` claimed
            // PlatformSignalled + RouteAndInternet. Fail loudly instead — this is the one Android case
            // that is dead rather than degraded, and a caller who wants to survive it can fall back to
            // PollingNetworkMonitor, which needs no permission.
            throw NetworkMonitorPermissionException(cause = e)
        }
    }

    /**
     * Resolves the state synchronously so the constructor returns with a real answer rather than
     * [NetworkState.Unknown]. This is the one place a synchronous `getNetworkCapabilities` is correct: it
     * runs on the constructing thread before any callback is registered, not inside a callback.
     */
    private fun seedInitialState() {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) {
            _state.value = NetworkState.Offline
            return
        }
        publish(activeNetwork, connectivityManager.getNetworkCapabilities(activeNetwork))
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}

/**
 * Pure mapper from the default network's capability bits to a [NetworkState] — the whole Android rung
 * table of RFC_NETWORK_REACHABILITY §4, unit-testable without a device or Robolectric.
 *
 * | Capabilities on the default network | Result |
 * |---|---|
 * | no `INTERNET` | [NetworkState.LinkLocal] — a link, but nothing routes off it |
 * | `INTERNET` + `VALIDATED` + `NOT_SUSPENDED` | `Routable(id, Confirmed)` |
 * | `CAPTIVE_PORTAL` | `Routable(id, Blocked(CaptivePortal))` |
 * | not `NOT_SUSPENDED` | `Routable(id, Blocked(Suspended))` |
 * | `INTERNET`, none of the above | `Routable(id, Pending)` — the validation window |
 *
 * [InternetAccess.Observed.Limited] is deliberately **not** produced here. Android's analogue of
 * NetworkManager's `LIMITED` is `NET_CAPABILITY_PARTIAL_CONNECTIVITY`, which is `@SystemApi` — absent
 * from the public SDK at any compileSdk, so a library cannot read it without reflecting on a hidden
 * constant. A partially-connected Android network therefore lands on [InternetAccess.Observed.Pending]
 * or `Confirmed` depending on whether the platform validated it, which is what the platform itself
 * tells apps. `Limited` stays reachable through a scripted fixture and will be produced by the deferred
 * Linux + NetworkManager tier (RFC §8.2).
 *
 * Order matters where the bits overlap, and it is ordered by **what the consumer must do**: the two
 * states that say "do not attempt" ([BlockReason.CaptivePortal] needs a human, [BlockReason.Suspended]
 * needs waiting) are decided before the two that say "attempt". A portal-intercepted network can
 * legitimately also be `VALIDATED` on some builds, and a suspended link keeps `INTERNET` — reading
 * `VALIDATED` first would report both as `Confirmed`, which is the pre-RFC bug. Within "do not
 * attempt", `CAPTIVE_PORTAL` is checked before `!NOT_SUSPENDED` deliberately: a suspended link behind
 * a portal reports [needsUserAction] rather than [isTransient] — when both verdicts apply, the
 * user-actionable one wins, because waiting cannot clear a portal.
 *
 * [NetworkState.Offline] is not produced here: the absence of a default network is the caller's
 * observation, not a property of a capabilities object.
 */
internal fun androidNetworkState(
    id: NetworkId,
    hasInternet: Boolean,
    hasValidated: Boolean,
    hasCaptivePortal: Boolean,
    notSuspended: Boolean,
): NetworkState =
    when {
        !hasInternet -> NetworkState.LinkLocal(id)
        hasCaptivePortal -> NetworkState.Routable(id, InternetAccess.Observed.Blocked(BlockReason.CaptivePortal))
        !notSuspended -> NetworkState.Routable(id, InternetAccess.Observed.Blocked(BlockReason.Suspended))
        hasValidated -> NetworkState.Routable(id, InternetAccess.Observed.Confirmed)
        else -> NetworkState.Routable(id, InternetAccess.Observed.Pending)
    }

/**
 * Pure mapper from `NetworkCapabilities` transport bits + `networkHandle` to a typed [NetworkId]
 * (unit-tested without a device). A VPN network's capabilities also list the transport it tunnels
 * over, so `hasVpn` wins and the remaining bits become [NetworkKind.Vpn.transports] — `Vpn(over
 * Wi-Fi)` and `Vpn(over cellular)` are different networks for the cache scope. No recognized
 * transport at all → [NetworkId.Unidentified].
 *
 * [handle] is `Network.getNetworkHandle()`, which needs API 23 — the floor this module now declares, so
 * unlike before there is no kind-only degradation path (RFC §8.1).
 */
internal fun androidNetworkId(
    hasWifi: Boolean,
    hasCellular: Boolean,
    hasEthernet: Boolean,
    hasVpn: Boolean,
    handle: Long,
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
    return NetworkId.Link(kind, handle)
}

/**
 * Accept/ignore decision for a network callback, pure so the full matrix is unit-testable without
 * Robolectric. Handles stand in for [Network] instances: `Network` equality is its netId, and
 * `networkHandle` is derived from that same netId, so handle equality *is* network equality.
 *
 * Why a gate exists at all: below O the monitor registers an `INTERNET` [NetworkRequest], and
 * `registerNetworkCallback` fires for **every** network satisfying the request — with Wi-Fi associated
 * and cell data enabled (the ordinary phone state), `onCapabilitiesChanged` interleaves for two live
 * networks on a completely stable device. Publishing each one flapped [NetworkMonitor.state] between
 * two [NetworkId.Link]s; on the old two-flow surface that only jittered the identity flow, but now the
 * same flap drives `pathChanges()` — and QUIC auto-migration — into spurious migrations. The process's
 * **default** network is the tie-break authority (the same one `seedInitialState` already uses to
 * answer "which network are we on"), so a non-default network's chatter is ignored.
 *
 * [activeHandle] is nullable because `getActiveNetwork()` is transiently null mid-handoff. In that
 * window the network already reflected in state ([trackedHandle]) is still accepted, so an in-place
 * capability change on the link we track lands rather than being dropped; when nothing is tracked yet
 * either, any network is accepted — a wrong first pick is corrected by the next callback once the
 * default reappears. [trackedHandle] is also accepted when a *different* default exists, for the same
 * reason: state still names that network, and a stale value is worse than a late switch.
 *
 * O+ ([tracksDefaultNetwork]) needs none of this: `registerDefaultNetworkCallback` already scopes every
 * callback to the single default network, so the decision is constantly `true`.
 */
internal fun acceptsNetworkUpdate(
    tracksDefaultNetwork: Boolean,
    candidateHandle: Long,
    activeHandle: Long?,
    trackedHandle: Long?,
): Boolean =
    when {
        tracksDefaultNetwork -> true
        activeHandle == null && trackedHandle == null -> true
        else -> candidateHandle == activeHandle || candidateHandle == trackedHandle
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
