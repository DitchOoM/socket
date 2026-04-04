package com.ditchoom.socket

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

/**
 * JavaScript [NetworkMonitor].
 *
 * - **Node.js**: polls `os.networkInterfaces()` for non-loopback interfaces.
 * - **Browser**: uses `navigator.onLine` and `online`/`offline` events on `window`.
 *
 * @param interval Polling interval for Node.js (ignored in browser where events are used).
 */
class JsNetworkMonitor(
    private val interval: Duration = 5.seconds,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()
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
        }
        js("window").addEventListener("offline") { _: dynamic ->
            _availability.value = NetworkAvailability.UNAVAILABLE
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Creates a JavaScript [NetworkMonitor].
 *
 * @param interval Polling interval for Node.js (ignored in browser).
 */
fun NetworkMonitor.Companion.create(interval: Duration = 5.seconds): NetworkMonitor = JsNetworkMonitor(interval)
