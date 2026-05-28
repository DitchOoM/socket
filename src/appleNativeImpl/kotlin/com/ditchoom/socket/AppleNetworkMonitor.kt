@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_create_path_monitor
import com.ditchoom.socket.nwhelpers.nw_helper_path_monitor_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_path_monitor_set_update_handler
import com.ditchoom.socket.nwhelpers.nw_helper_path_monitor_start
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apple [NetworkMonitor] backed by `NWPathMonitor` from Network.framework.
 *
 * Event-driven: the OS calls back immediately on any network path change
 * (wifi ↔ cellular, VPN connect/disconnect, airplane mode, etc.).
 */
class AppleNetworkMonitor : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val monitor = nw_helper_create_path_monitor()

    init {
        nw_helper_path_monitor_set_update_handler(monitor) { status ->
            // nw_path_status_t: 0=invalid, 1=satisfied, 2=unsatisfied, 3=requiresConnection
            _availability.value =
                when (status) {
                    1 -> NetworkAvailability.AVAILABLE
                    2, 3 -> NetworkAvailability.UNAVAILABLE
                    else -> NetworkAvailability.UNKNOWN
                }
        }
        nw_helper_path_monitor_start(monitor)
    }

    override fun close() {
        nw_helper_path_monitor_cancel(monitor)
    }
}

/** Creates an Apple [NetworkMonitor] backed by `NWPathMonitor`. */
fun NetworkMonitor.Companion.apple(): NetworkMonitor = AppleNetworkMonitor()
