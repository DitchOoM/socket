@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Linux [NetworkMonitor] using netlink sockets for event-driven network change detection.
 *
 * Uses `AF_NETLINK` / `NETLINK_ROUTE` with `RTMGRP_LINK | RTMGRP_IPV4_IFADDR` multicast
 * groups to receive kernel notifications when network interfaces change state or gain/lose
 * addresses. On each notification, re-checks actual state via `getifaddrs()`.
 *
 * This hybrid approach avoids parsing complex netlink message attributes while still
 * being event-driven (no polling).
 */
class LinuxNetworkMonitor : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var netlinkFd: Int = -1

    init {
        netlinkFd = createNetlinkSocket()
        _availability.value = checkInterfaces()

        if (netlinkFd >= 0) {
            scope.launch {
                // Allocate a native scratch buffer once, reuse across netlink recvs.
                // Deterministic (malloc/free) so it's freed when the coroutine exits —
                // no GC-managed ByteArray, no per-iteration pin/unpin.
                val scratch = BufferFactory.deterministic().allocate(4096)
                try {
                    val ptr =
                        scratch.nativeMemoryAccess!!
                            .nativeAddress
                            .toCPointer<ByteVar>()!!
                    while (isActive) {
                        val n = recv(netlinkFd, ptr, 4096.toULong(), 0)
                        if (n <= 0) break
                        _availability.value = checkInterfaces()
                    }
                } finally {
                    scratch.freeNativeMemory()
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        if (netlinkFd >= 0) {
            close(netlinkFd)
            netlinkFd = -1
        }
    }

    companion object {
        private fun createNetlinkSocket(): Int =
            memScoped {
                val fd = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE)
                if (fd < 0) return -1

                val addr = alloc<sockaddr_nl>()
                addr.nl_family = AF_NETLINK.toUShort()
                addr.nl_pid = 0u
                addr.nl_groups = (RTMGRP_LINK or RTMGRP_IPV4_IFADDR).toUInt()

                val bindResult = socket_bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_nl>().toUInt())
                if (bindResult < 0) {
                    close(fd)
                    return -1
                }
                fd
            }

        internal fun checkInterfaces(): NetworkAvailability =
            memScoped {
                val ifaddrsPtr = allocPointerTo<ifaddrs>()
                if (getifaddrs(ifaddrsPtr.ptr) != 0) return NetworkAvailability.UNKNOWN

                var current = ifaddrsPtr.value
                var hasNonLoopback = false
                while (current != null) {
                    val flags = current.pointed.ifa_flags.toInt()
                    val isUp = (flags and IFF_UP) != 0
                    val isLoopback = (flags and IFF_LOOPBACK) != 0
                    if (isUp && !isLoopback) {
                        hasNonLoopback = true
                        break
                    }
                    current = current.pointed.ifa_next
                }
                freeifaddrs(ifaddrsPtr.value)

                if (hasNonLoopback) NetworkAvailability.AVAILABLE else NetworkAvailability.UNAVAILABLE
            }
    }
}

/** Creates a Linux [NetworkMonitor] using netlink sockets (event-driven). */
fun NetworkMonitor.Companion.create(): NetworkMonitor = LinuxNetworkMonitor()
