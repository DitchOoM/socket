package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JavaScript [NetworkMonitor].
 *
 * - **Node.js**: polls `os.networkInterfaces()` for non-loopback interfaces. Identity stays
 *   [NetworkId.Unidentified] — Node has no link-kind API and interface-name heuristics are wrong
 *   cross-platform.
 * - **Browser**: uses `navigator.onLine` and `online`/`offline` events on `window`; identity is
 *   the coarse [NetworkId.KindOnly] from `navigator.connection.type` where the Network Information
 *   API exists (Chromium), [NetworkId.Unidentified] elsewhere (Safari/Firefox).
 *
 * Both are [ReachResolution.LinkOnly], and neither ever reports [NetworkState.LinkLocal] — see
 * [jsNetworkState]. `navigator.onLine` and an interface list say a link exists and nothing about routes,
 * and asserting "there is a link but no route off it" is a claim only a monitor that can *see* routes is
 * entitled to make (RFC_NETWORK_REACHABILITY §9.2). So an up link is reported optimistically as
 * `Routable(id, Unobserved)`, and a consumer that needs more learns from [capability] before subscribing.
 *
 * @param interval Polling interval for Node.js (ignored in browser where events are used).
 */
class JsNetworkMonitor(
    private val interval: Duration = 5.seconds,
) : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    /**
     * Browser only: one bump per pushed event (`online`, `offline`, Network Information `change`). The
     * Node path is [MonitorMechanism.Polled] and deliberately leaves this at the contract default —
     * never advances — because a poll's cadence is configuration, not a property of the network
     * (see [NetworkMonitor.observationCount]).
     */
    private val _observationCount = MutableStateFlow(0L)
    override val observationCount: StateFlow<Long> = _observationCount.asStateFlow()

    /**
     * Node polls `os.networkInterfaces()`; the browser is pushed `online`/`offline` (plus the Network
     * Information API's `change` where it exists). The mechanism is resolved from the same [isNodeJsRuntime]
     * check the constructor branches on, so it can never disagree with what was actually wired; the
     * resolution is [ReachResolution.LinkOnly] on both, because neither runtime can see a routing table.
     */
    override val capability: MonitorCapability =
        MonitorCapability(
            if (isNodeJsRuntime) MonitorMechanism.Polled(interval) else MonitorMechanism.PlatformSignalled,
            ReachResolution.LinkOnly,
        )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        if (isNodeJsRuntime) {
            scope.launch {
                while (isActive) {
                    _state.value = checkNodeNetwork()
                    delay(interval)
                }
            }
        } else {
            initBrowserMonitor()
        }
    }

    /**
     * Node's link observation: `os.networkInterfaces()` names every interface, so a non-loopback key
     * means a link exists. Node exposes no link *kind*, so identity stays [NetworkId.Unidentified] and
     * `pathChanges()` is correctly inert here rather than emitting a fabricated change.
     *
     * A throw is [NetworkState.Unknown], never [NetworkState.Offline] — "the lookup failed" is not "there
     * is no network".
     */
    private fun checkNodeNetwork(): NetworkState =
        try {
            val interfaces = js("require('os').networkInterfaces()")
            val keys: Array<String> = js("Object.keys")(interfaces) as Array<String>
            val hasNonLoopback = keys.any { name -> name != "lo" && name != "lo0" }
            jsNetworkState(hasLink = hasNonLoopback, id = NetworkId.Unidentified)
        } catch (_: Throwable) {
            NetworkState.Unknown
        }

    private fun initBrowserMonitor() {
        refreshBrowserState()
        // online/offline and the Network Information API's `change` all mutate the SAME value, so every
        // one of them republishes the whole state — reachability and identity can never be sampled apart.
        js("window").addEventListener("online") { _: dynamic -> observedBrowserEvent() }
        js("window").addEventListener("offline") { _: dynamic -> observedBrowserEvent() }
        // Network Information API (Chromium): fires on connection-type transitions (wifi↔cellular).
        val connection = js("navigator.connection || null")
        if (connection != null) {
            connection.addEventListener("change") { _: dynamic -> observedBrowserEvent() }
        }
    }

    /** A pushed browser event: count the observation (the initial synchronous read does not count). */
    private fun observedBrowserEvent() {
        _observationCount.update { it + 1 }
        refreshBrowserState()
    }

    /** Read both browser facts — `navigator.onLine` and the connection type — and publish one state. */
    private fun refreshBrowserState() {
        val online =
            try {
                js("navigator.onLine") as Boolean
            } catch (_: Throwable) {
                // A runtime without navigator.onLine tells us nothing; Unknown, not Offline.
                _state.value = NetworkState.Unknown
                return
            }
        val type =
            try {
                js("(navigator.connection && navigator.connection.type) || null") as? String
            } catch (_: Throwable) {
                null
            }
        _state.value = jsNetworkState(hasLink = online, id = browserConnectionTypeToNetworkId(type))
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Pure mapper from "is a link up" plus whatever identity the runtime could resolve to a [NetworkState] —
 * the JS row of RFC_NETWORK_REACHABILITY §4, shared by the Node and browser paths because both observe
 * exactly the same thing: a link, and nothing about routes.
 *
 * | Fact | Result |
 * |---|---|
 * | a link exists (`navigator.onLine`, a non-loopback interface) | `Routable(id, Unobserved)` |
 * | no link | [NetworkState.Offline] |
 *
 * The optimistic rung is the point (RFC §9.2). The draft had [ReachResolution.LinkOnly] report
 * [NetworkState.LinkLocal], which was the one outright contradiction in it: asserting "a link is up but
 * nothing routes off it" *requires* route visibility, which neither Node nor a browser has — and browsers
 * route off-link and cannot multicast at all, so `LinkLocal` is precisely the wrong rung for them. Under
 * that reading an online browser would have reported [canRouteOffLink] `== false` and refused to connect.
 * [ReachResolution.permits] enforces this, and `jsNetworkStateNeverReportsLinkLocal` proves it.
 */
internal fun jsNetworkState(
    hasLink: Boolean,
    id: NetworkId,
): NetworkState =
    if (hasLink) {
        NetworkState.Routable(id, InternetAccess.Unobserved)
    } else {
        NetworkState.Offline
    }

/**
 * Pure mapper from the Network Information API's `connection.type` to a typed [NetworkId]. Browsers
 * expose no per-link handle, so identity is the coarse [NetworkId.KindOnly] — still enough for the
 * decisive Wi-Fi↔Cellular transition (RFC_TRANSPORT_FALLBACK §12). `none`/`unknown`/absent →
 * [NetworkId.Unidentified].
 */
internal fun browserConnectionTypeToNetworkId(type: String?): NetworkId =
    when (type) {
        "wifi" -> NetworkId.KindOnly(NetworkKind.Wifi)
        "cellular" -> NetworkId.KindOnly(NetworkKind.Cellular)
        "ethernet" -> NetworkId.KindOnly(NetworkKind.Ethernet)
        null, "none", "unknown" -> NetworkId.Unidentified
        else -> NetworkId.KindOnly(NetworkKind.Other(type))
    }

/**
 * Creates a JavaScript [NetworkMonitor].
 *
 * @param interval Polling interval for Node.js (ignored in browser).
 */
fun NetworkMonitor.Companion.create(interval: Duration = 5.seconds): NetworkMonitor = JsNetworkMonitor(interval)
