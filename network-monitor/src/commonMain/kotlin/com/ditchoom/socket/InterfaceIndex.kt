package com.ditchoom.socket

import kotlin.jvm.JvmInline

/**
 * An OS network-interface index — the stable per-link handle the kernel assigns each interface. A
 * [value class][JvmInline] so it can't be silently swapped with an unrelated [Int]/[Long] (a route
 * metric, a port, a count) at a call site. Its [value] is the same number a
 * [NetworkId.Link.handle][com.ditchoom.socket.transport.NetworkId.Link.handle] carries, so a gathered
 * candidate can be tied back to the network the [NetworkMonitor] reports as primary.
 *
 * Lives here rather than beside `NetworkInterfaceInfo` in `:socket` because `LinuxNetworkMonitor`
 * types its resolved default-route interface with it, and a monitor in this module cannot reach back
 * into `:socket`. `:socket` re-exports it through `api(project(":network-monitor"))`, in the same
 * package it has always been in, so nothing downstream changes an import.
 */
@JvmInline
value class InterfaceIndex(
    val value: Long,
)
