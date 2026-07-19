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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Node-vs-browser detection, module-local to :network-monitor (root :socket has its own copy in
// Socket.kt, which this module does not depend on): the browser has `window`, Node does not.
private val isNodeJs: Boolean = js("global.window") == null

/**
 * JavaScript [NetworkMonitor].
 *
 * - **Node.js**: polls `os.networkInterfaces()` for non-loopback interfaces. [networkId] stays
 *   [NetworkId.Unidentified] — Node has no link-kind API and interface-name heuristics are wrong
 *   cross-platform.
 * - **Browser**: uses `navigator.onLine` and `online`/`offline` events on `window`; [networkId] is
 *   the coarse [NetworkId.KindOnly] from `navigator.connection.type` where the Network Information
 *   API exists (Chromium), [NetworkId.Unidentified] elsewhere (Safari/Firefox).
 *
 * @param interval Polling interval for Node.js (ignored in browser where events are used).
 */
class JsNetworkMonitor(
    private val interval: Duration = 5.seconds,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val _networkId = MutableStateFlow<NetworkId>(NetworkId.Unidentified)
    override val networkId: StateFlow<NetworkId> = _networkId.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        if (isNodeJs) {
            scope.launch {
                while (isActive) {
                    _availability.value = checkNodeNetwork()
                    delay(interval)
                }
            }
        } else {
            initBrowserMonitor()
        }
    }

    private fun checkNodeNetwork(): NetworkAvailability =
        try {
            val interfaces = js("require('os').networkInterfaces()")
            val keys: Array<String> = js("Object.keys")(interfaces) as Array<String>
            val hasNonLoopback = keys.any { name -> name != "lo" && name != "lo0" }
            if (hasNonLoopback) NetworkAvailability.AVAILABLE else NetworkAvailability.UNAVAILABLE
        } catch (_: Throwable) {
            NetworkAvailability.UNKNOWN
        }

    private fun initBrowserMonitor() {
        _availability.value =
            if (js("navigator.onLine") as Boolean) {
                NetworkAvailability.AVAILABLE
            } else {
                NetworkAvailability.UNAVAILABLE
            }
        js("window").addEventListener("online") { _: dynamic ->
            _availability.value = NetworkAvailability.AVAILABLE
            refreshBrowserNetworkId()
        }
        js("window").addEventListener("offline") { _: dynamic ->
            _availability.value = NetworkAvailability.UNAVAILABLE
            refreshBrowserNetworkId()
        }
        refreshBrowserNetworkId()
        // Network Information API (Chromium): fires on connection-type transitions (wifi↔cellular).
        val connection = js("navigator.connection || null")
        if (connection != null) {
            connection.addEventListener("change") { _: dynamic ->
                refreshBrowserNetworkId()
            }
        }
    }

    private fun refreshBrowserNetworkId() {
        val type =
            try {
                js("(navigator.connection && navigator.connection.type) || null") as? String
            } catch (_: Throwable) {
                null
            }
        _networkId.value = browserConnectionTypeToNetworkId(type)
    }

    override fun close() {
        scope.cancel()
    }
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
