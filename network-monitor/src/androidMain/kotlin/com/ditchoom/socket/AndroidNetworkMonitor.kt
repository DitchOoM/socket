package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
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
 * A fourth signal arrives on a callback of its own: the **per-UID blocked verdict**
 * (`onBlockedStatusChanged`, API 29+). Under Data Saver / background restriction the network's
 * capabilities keep reading `INTERNET|VALIDATED` while the platform passes none of this app's traffic,
 * so the verdict is folded into the same published [NetworkState] (as
 * [InternetAccess.Observed.Blocked] ([BlockReason.Suspended])) rather than surfaced beside it — see
 * [androidNetworkState].
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    /**
     * One bump per [ConnectivityManager.NetworkCallback] delivery for the default network. The
     * constructor's synchronous seed is not a platform observation and does not count.
     */
    private val observationRelay = ObservationRelay(_state)
    override val observationCount: StateFlow<Long> = observationRelay.count
    override val observations: Flow<NetworkObservation> = observationRelay.observations

    /**
     * From the same capabilities object every state publication reads:
     * [android.net.NetworkCapabilities.getSignalStrength] (API 29+) rides each `onCapabilitiesChanged`,
     * so no extra OS observer, no location permission, and no polling. Below API 29 the getter does not
     * exist and the capability honestly declares [LinkQualityResolution.None].
     */
    private val _linkQuality = MutableStateFlow<LinkQuality>(LinkQuality.Unavailable)
    override val linkQuality: StateFlow<LinkQuality> = _linkQuality.asStateFlow()

    override val capability: MonitorCapability =
        MonitorCapability(
            MonitorMechanism.PlatformSignalled,
            ReachResolution.RouteAndInternet,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                LinkQualityResolution.Rssi
            } else {
                LinkQualityResolution.None
            },
        )

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * The addresses of the network [state] names, from its `LinkProperties`. Written before the state
     * naming that network, so a link's addresses are here no later than the state naming it.
     */
    private val _linkAddresses = MutableStateFlow<LinkAddresses>(LinkAddresses.Reported(emptyMap()))
    override val linkAddresses: StateFlow<LinkAddresses> = _linkAddresses.asStateFlow()

    /**
     * The network currently reflected in [state], so a stale
     * [onLost][ConnectivityManager.NetworkCallback.onLost] can't clear a newer one.
     *
     * Written from the framework's internal callback Handler and read on the same, but also written by
     * the constructor on the caller's thread, so it is [Volatile] rather than a plain field.
     */
    @Volatile
    private var currentNetwork: Network? = null

    /**
     * The last capabilities delivered for [currentNetwork], retained so
     * [onBlockedStatusChanged][ConnectivityManager.NetworkCallback.onBlockedStatusChanged] — which
     * carries only the blocked bit — can re-fold the verdict into one coherent [NetworkState] instead of
     * publishing it on a second surface. Written and read on the framework's callback Handler; [Volatile]
     * for the same constructor-thread seed reason as [currentNetwork].
     */
    @Volatile
    private var currentCaps: AndroidNetworkCapabilities? = null

    /**
     * The per-UID blocked verdict, keyed to the [Network] it was delivered for so a verdict about a dead
     * network can never leak onto its successor: [fold] applies [blockedForApp] only while
     * [blockedNetwork] still equals the network being published, which makes resets on handover
     * unnecessary rather than easy to forget.
     */
    @Volatile
    private var blockedNetwork: Network? = null

    @Volatile
    private var blockedForApp: Boolean = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Deliberately does NOT call getNetworkCapabilities(network). The platform javadoc:
                // "Do NOT call getNetworkCapabilities(Network) ... in this callback as this is prone to
                // race conditions ... may return an outdated or even a null object. Instead, wait for a
                // call to onCapabilitiesChanged ... whose arguments are guaranteed to be well-ordered."
                // onCapabilitiesChanged is guaranteed to follow immediately, and it publishes one
                // coherent NetworkState. Publishing here too would open a window where a consumer sees
                // the new network's reachability beside the old network's identity — precisely the
                // torn read collapsing to one value was meant to make impossible.
            }

            override fun onLost(network: Network) {
                // For registerDefaultNetworkCallback the platform "will only be invoked against the
                // last network returned by onAvailable() when that network is lost AND no other
                // network satisfies the criteria of the request" — so there is genuinely nothing
                // left to fall back to and no stale-onLost race to guard against. A handover to a
                // better network arrives as onAvailable/onCapabilitiesChanged instead, never here.
                clear()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: AndroidNetworkCapabilities,
            ) {
                observationRelay.record(fold(network, caps))
                publishLinkQuality(caps)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                // An address added to or removed from the network already named; its identity is unchanged.
                if (network != currentNetwork) return
                _linkAddresses.value = androidLinkAddresses(_state.value.networkId, linkProperties.addressLiterals())
            }

            override fun onBlockedStatusChanged(
                network: Network,
                blocked: Boolean,
            ) {
                // Under Data Saver / background restriction the platform delivers the per-UID verdict
                // HERE, while the network's own capabilities keep reading INTERNET|VALIDATED — so a
                // monitor that only folds onCapabilitiesChanged keeps publishing Confirmed while every
                // socket the app opens fails. The verdict must ride the same published value as the
                // capability bits (one atomic value, RFC_NETWORK_REACHABILITY §1.2), so it is folded
                // into the state rather than exposed as a second flow.
                //
                // This override only ever runs on API 29+ (the platform added it there), where the
                // registration contract orders onCapabilitiesChanged before onBlockedStatusChanged —
                // so by the time a verdict lands for the current network, its capabilities are already
                // retained in currentCaps and the re-fold below cannot tear.
                blockedNetwork = network
                blockedForApp = blocked
                // A verdict for a network that is not the tracked one changes nothing to publish, but the
                // platform still spoke — so it counts, exactly as it did before, via the unchanged path.
                if (network == currentNetwork) {
                    val caps = currentCaps
                    observationRelay.record(fold(network, caps))
                    publishLinkQuality(caps)
                } else {
                    observationRelay.recordUnchanged()
                }
            }
        }

    /** The link is gone: forget it and record the loss as the observation it is. */
    private fun clear() {
        currentNetwork = null
        currentCaps = null
        _linkAddresses.value = LinkAddresses.Reported(emptyMap())
        observationRelay.record(NetworkState.Offline)
        // No link, no measurement — never let a dead link's last reading linger as if it were current.
        _linkQuality.value = LinkQuality.Unavailable
    }

    /**
     * Fold one coherent [NetworkState] — identity, reachability and the per-UID blocked verdict together
     * — and retain what it was folded from.
     *
     * Returns the state rather than publishing it, so the caller decides whether this is a counted
     * observation ([ObservationRelay.record]) or the synchronous seed ([ObservationRelay.publish]). The
     * relay publishes either way, which is what keeps a state and its sequence indivisible.
     *
     * [publishLinkQuality] is deliberately *not* folded in here: it has to run after the state is
     * published, not before, and only a caller holding both halves can order them.
     */
    private fun fold(
        network: Network,
        caps: AndroidNetworkCapabilities?,
    ): NetworkState {
        currentNetwork = network
        currentCaps = caps
        val folded =
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
                // The ABSENCE of NOT_SUSPENDED is the suspended signal.
                notSuspended = caps.has(AndroidNetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                // Applies only while the verdict still names the network being published — a verdict
                // for a superseded network must not brand its successor. The seed path runs before any
                // verdict has arrived, so it publishes unblocked and the registration replay (which on
                // API 29+ includes onBlockedStatusChanged) corrects it within milliseconds — the same
                // seed-then-callback-authoritative contract the constructor already documents.
                blockedForApp = blockedForApp && network == blockedNetwork,
            )
        // Before the state is published, which the caller does with the value returned.
        _linkAddresses.value =
            androidLinkAddresses(folded.networkId, connectivityManager.getLinkProperties(network)?.addressLiterals().orEmpty())
        return folded
    }

    /**
     * Publish the RSSI reading these capabilities carry, **after** the state they were folded with.
     *
     * The order is the one this class has always published in: [state] is the primary signal and moves
     * first, [linkQuality] follows. They are separate flows by design (a reading is not reachability), so
     * a consumer watching both can see one move before the other either way — but flipping which one
     * leads would be a silent change in what an existing collector observes.
     */
    private fun publishLinkQuality(caps: AndroidNetworkCapabilities?) {
        _linkQuality.value =
            androidLinkQuality(
                signalStrength =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                        caps.signalStrength
                    } else {
                        null
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
            connectivityManager.registerDefaultNetworkCallback(callback)
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
            // publish, not record: a seed is the constructor asking, not the platform speaking.
            observationRelay.publish(NetworkState.Offline)
            return
        }
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        observationRelay.publish(fold(activeNetwork, caps))
        publishLinkQuality(caps)
    }

    /** Flips once: the platform throws on a second `unregisterNetworkCallback`, so only the winner of this CAS unregisters. */
    private val unregistered = AtomicBoolean(false)

    /** Unregister the callback. Closing twice (or from two threads) unregisters once. */
    override fun close() {
        if (unregistered.compareAndSet(false, true)) {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

/**
 * Pure mapper from the default network's capability bits to a [NetworkState] — the whole Android rung
 * table of RFC_NETWORK_REACHABILITY §4, unit-testable without a device or Robolectric.
 *
 * | Capabilities on the default network | Result |
 * |---|---|
 * | no `INTERNET` | [NetworkState.LinkLocal] — a link, but nothing routes off it |
 * | `INTERNET` + `VALIDATED` + `NOT_SUSPENDED`, not blocked for this app | `Routable(id, Confirmed)` |
 * | `CAPTIVE_PORTAL` | `Routable(id, Blocked(CaptivePortal))` |
 * | not `NOT_SUSPENDED`, **or** blocked for this app (`onBlockedStatusChanged`) | `Routable(id, Blocked(Suspended))` |
 * | `INTERNET`, none of the above | `Routable(id, Pending)` — the validation window |
 *
 * [blockedForApp] is the per-UID verdict from
 * [onBlockedStatusChanged][ConnectivityManager.NetworkCallback.onBlockedStatusChanged] (Data Saver,
 * background restriction): the network itself is fine — its capabilities keep reading
 * `INTERNET|VALIDATED` — but the platform passes none of *this app's* traffic. It folds onto
 * [BlockReason.Suspended] rather than a new [BlockReason] because it prescribes the same consumer
 * response: transient — the platform promises a follow-up verdict when the app is foregrounded or the
 * restriction lifts — so wait, do not tear down, and retrying meanwhile is futile. A network-wide
 * suspension and a per-UID pause are different causes with the same treatment, and [BlockReason] is
 * deliberately keyed on treatment.
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
    blockedForApp: Boolean,
): NetworkState =
    when {
        !hasInternet -> NetworkState.LinkLocal(id)
        hasCaptivePortal -> NetworkState.Routable(id, InternetAccess.Observed.Blocked(BlockReason.CaptivePortal))
        !notSuspended || blockedForApp ->
            NetworkState.Routable(id, InternetAccess.Observed.Blocked(BlockReason.Suspended))
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

/** The addresses of [LinkProperties], as the numeric literals the platform prints. */
private fun LinkProperties.addressLiterals(): List<String> = linkAddresses.mapNotNull { it.address.hostAddress }

/** A view holding the one network the monitor names, [id], carrying [addresses]. */
internal fun androidLinkAddresses(
    id: NetworkId,
    addresses: List<String>,
): LinkAddresses =
    LinkAddresses.Reported(
        when (id) {
            NetworkId.Unidentified -> emptyMap()
            is NetworkId.KindOnly, is NetworkId.Link ->
                mapOf(id to numericAddressesOf(addresses))
        },
    )

/**
 * Pure mapper from `NetworkCapabilities.getSignalStrength()` to a [LinkQuality] (unit-testable without
 * a device). [signalStrength] is `null` where the getter cannot be read at all (below API 29, or no
 * capabilities object); [AndroidNetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED] is the platform's own
 * in-band "no measurement" sentinel — a wired link, or an agent that never filled the field. Both are
 * the same honest answer: [LinkQuality.Unavailable], never a fabricated number.
 *
 * The value is passed through unedited. For Wi-Fi it is the RSSI in dBm; telephony reports a
 * bearer-specific level on the same field — monotone in quality either way (see [LinkQuality.Rssi]).
 */
internal fun androidLinkQuality(signalStrength: Int?): LinkQuality =
    if (signalStrength == null || signalStrength == AndroidNetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
        LinkQuality.Unavailable
    } else {
        LinkQuality.Rssi(signalStrength)
    }

/**
 * Creates an Android [NetworkMonitor] backed by [ConnectivityManager].
 *
 * @param context Application context (use `applicationContext` to avoid Activity leaks).
 */
fun NetworkMonitor.Companion.android(context: Context): NetworkMonitor = AndroidNetworkMonitor(context)

/**
 * Eagerly install a `ConnectivityManager`-backed [NetworkMonitor] as the process default, so QUIC
 * auto-migration (and any other [NetworkMonitor.shared] consumer) uses it.
 *
 * ```kotlin
 * NetworkMonitor.installAndroidContext(applicationContext)
 * ```
 *
 * **Normally unnecessary.** [NetworkMonitorInitializer] hands this module the application `Context` via
 * androidx.startup before any app code runs, so the zero-arg `NetworkMonitor.open()` already returns
 * a reactive monitor and `shared()` builds one lazily on first use. Call this only to override
 * that with a monitor built from a specific [Context], or when the initializer was removed from the
 * merged manifest.
 *
 * Unlike App Startup's capture-only path, this constructs the monitor **immediately** — it registers a
 * `ConnectivityManager.NetworkCallback` at the moment of the call — and the installed instance is
 * caller-owned: nothing here closes it. Uses [Context.getApplicationContext] to avoid leaking an
 * Activity, and also records that context for [androidOrNull] so a later `open()` stays reactive.
 */
fun NetworkMonitor.Companion.installAndroidContext(context: Context) {
    val application = context.applicationContext
    installAndroidApplicationContext(application)
    installProcessDefault(android(application))
}
