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
import java.net.NetworkInterface
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JVM [NetworkMonitor] that polls [NetworkInterface.getNetworkInterfaces].
 *
 * Reports [NetworkAvailability.AVAILABLE] when at least one non-loopback
 * interface is up. Polling is the only portable option on JVM desktop
 * (no event-driven network change API exists in the JDK).
 *
 * @param interval How often to check network interfaces (default 5 seconds).
 * @param checkNetwork Injectable check function for testing.
 */
class PollingNetworkMonitor(
    private val interval: Duration = 5.seconds,
    private val checkNetwork: () -> NetworkAvailability = Companion::defaultCheck,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val _networkId = MutableStateFlow<com.ditchoom.socket.transport.NetworkId>(com.ditchoom.socket.transport.NetworkId.Unidentified)
    override val networkId: StateFlow<com.ditchoom.socket.transport.NetworkId> = _networkId.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                _availability.value = checkNetwork()
                _networkId.value = currentPrimaryNetworkId()
                delay(interval)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        internal fun defaultCheck(): NetworkAvailability =
            try {
                val hasUp =
                    NetworkInterface
                        .getNetworkInterfaces()
                        ?.toList()
                        ?.any { !it.isLoopback && it.isUp } == true
                if (hasUp) NetworkAvailability.AVAILABLE else NetworkAvailability.UNAVAILABLE
            } catch (_: Exception) {
                NetworkAvailability.UNKNOWN
            }
    }
}

/**
 * Creates a JVM [NetworkMonitor] that polls network interfaces.
 *
 * @param interval How often to check (default 5 seconds).
 */
fun NetworkMonitor.Companion.polling(interval: Duration = 5.seconds): NetworkMonitor = PollingNetworkMonitor(interval)
