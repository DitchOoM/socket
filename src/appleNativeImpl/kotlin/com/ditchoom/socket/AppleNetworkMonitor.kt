@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_create_path_monitor
import com.ditchoom.socket.nwhelpers.nw_helper_path_monitor_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_path_monitor_set_update_handler
import com.ditchoom.socket.nwhelpers.nw_helper_path_monitor_start
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apple [NetworkMonitor] backed by `NWPathMonitor` from Network.framework.
 *
 * Event-driven: the OS calls back immediately on any network path change
 * (wifi ↔ cellular, VPN connect/disconnect, airplane mode, etc.). The same callback carries the
 * path's primary-interface identity (`nw_interface_get_type` + `nw_interface_get_index`), which
 * feeds [networkId] as a typed [NetworkId.Link] — the per-network capability-cache key
 * (RFC_TRANSPORT_FALLBACK §6).
 */
class AppleNetworkMonitor : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val _networkId = MutableStateFlow<NetworkId>(NetworkId.Unidentified)
    override val networkId: StateFlow<NetworkId> = _networkId.asStateFlow()

    private val monitor = nw_helper_create_path_monitor()

    init {
        nw_helper_path_monitor_set_update_handler(monitor) { status, interfaceType, interfaceIndex, interfaceName, usesTypes ->
            // nw_path_status_t: 0=invalid, 1=satisfied, 2=unsatisfied, 3=requiresConnection
            _availability.value =
                when (status) {
                    1 -> NetworkAvailability.AVAILABLE
                    2, 3 -> NetworkAvailability.UNAVAILABLE
                    else -> NetworkAvailability.UNKNOWN
                }
            _networkId.value = appleNetworkId(status, interfaceType, interfaceIndex, interfaceName, usesTypes)
        }
        nw_helper_path_monitor_start(monitor)
    }

    override fun close() {
        nw_helper_path_monitor_cancel(monitor)
    }
}

/**
 * Pure mapper from the `nw_path` callback fields to a typed [NetworkId] (unit-tested without
 * Network.framework). An unsatisfied path or one with no interface is [NetworkId.Unidentified];
 * otherwise the primary interface becomes a [NetworkId.Link] keyed on the OS interface index.
 *
 * `nw_interface_type_other` (0) is where VPN tunnels surface — Network.framework has no explicit
 * VPN interface type, so a tunnel-style BSD name (`utun*`/`ipsec*`/`ppp*`/`tun*`/`tap*`) is mapped
 * to [NetworkKind.Vpn] carrying the underlying links from the path-wide uses-interface-type bits
 * ([usesTypes]: 1=wifi, 2=cellular, 4=wired) — `Vpn(over Wi-Fi)` and `Vpn(over cellular)` are
 * different networks for the cache scope. Any other unmapped type keeps its raw name as the
 * diagnostic-only [NetworkKind.Other].
 */
internal fun appleNetworkId(
    status: Int,
    interfaceType: Int,
    interfaceIndex: UInt,
    interfaceName: String?,
    usesTypes: Int,
): NetworkId {
    if (status != 1 || interfaceIndex == 0u) return NetworkId.Unidentified
    val kind =
        when (interfaceType) {
            1 -> NetworkKind.Wifi
            2 -> NetworkKind.Cellular
            3 -> NetworkKind.Ethernet
            0 -> {
                val name = interfaceName.orEmpty()
                if (VPN_NAME_PREFIXES.any { name.startsWith(it) }) {
                    NetworkKind.Vpn(
                        buildSet {
                            if (usesTypes and 1 != 0) add(NetworkKind.Wifi)
                            if (usesTypes and 2 != 0) add(NetworkKind.Cellular)
                            if (usesTypes and 4 != 0) add(NetworkKind.Ethernet)
                        },
                    )
                } else {
                    NetworkKind.Other(name.ifEmpty { "other" })
                }
            }
            else -> NetworkKind.Other(interfaceName ?: "type-$interfaceType")
        }
    return NetworkId.Link(kind, interfaceIndex.toLong())
}

private val VPN_NAME_PREFIXES = listOf("utun", "ipsec", "ppp", "tun", "tap")

/** Creates an Apple [NetworkMonitor] backed by `NWPathMonitor`. */
fun NetworkMonitor.Companion.apple(): NetworkMonitor = AppleNetworkMonitor()
