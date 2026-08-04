#ifndef NM_NETWORK_HELPERS_H
#define NM_NETWORK_HELPERS_H

// Apple C bridge for :network-monitor — the NWPathMonitor backing AppleNetworkMonitor, and the
// getifaddrs scan backing enumerateNetworkInterfaces(). Both moved out of :socket's nw_helpers.h with
// the code that calls them (issue #269); :socket now has no caller for either.
//
// Both exist for the same reason: Kotlin/Native cannot express these APIs directly.
// `nw_path_monitor_set_update_handler` takes an Objective-C block over an `nw_path_t`, and the
// accessors that make a path useful (`nw_path_enumerate_interfaces`, `nw_path_uses_interface_type`)
// are themselves block-taking or C-enum-returning. `getifaddrs` / `struct ifaddrs` are not exposed by
// `platform.posix` on Apple K/N at all (unlike Linux, where `platform.linux` declares `ifaddrs.h` and
// the same actual needs no cinterop). So each scan/projection happens here in C and only
// int32_t/uint32_t/NSString* cross back into Kotlin.
//
// This is a SEPARATE cinterop from :socket's NWHelpers, deliberately: with both modules on one
// classpath there must be exactly one declarer of each helper. Re-declaring <Network/Network.h> from
// two cinterops is fine — the framework headers are not ours, and two cinterops declaring them was
// measured to compile and link (see network-monitor/build.gradle.kts); it is duplicate declarations of
// OUR OWN helpers that must not happen.

#include <Network/Network.h>
#include <Foundation/Foundation.h>

// ============================================================
// Network path monitor
// ============================================================

// Path update handler — receives nw_path_status_t plus the path's primary-interface identity.
// status: 0=invalid, 1=satisfied, 2=unsatisfied, 3=satisfiable ("a connection attempt will trigger
//   network attachment"; Swift calls it .requiresConnection).
// interface_type (nw_interface_type_t of the first/primary interface): 0=other, 1=wifi, 2=cellular,
//   3=wired, 4=loopback; -1 when the path has no interface.
// interface_index: OS interface index of the primary interface; 0 when the path has no interface.
// interface_name: BSD name of the primary interface (e.g. "en0", "utun3"); nil when none.
// uses_interface_types: bitmask of nw_path_uses_interface_type over the whole path —
//   1=wifi, 2=cellular, 4=wired (identifies what a VPN tunnels over).
typedef void (^nm_path_update_handler_t)(
    int32_t status,
    int32_t interface_type,
    uint32_t interface_index,
    NSString * _Nullable interface_name,
    int32_t uses_interface_types);

nw_path_monitor_t _Nonnull nm_create_path_monitor(void);

void nm_path_monitor_set_update_handler(
    nw_path_monitor_t _Nonnull monitor,
    nm_path_update_handler_t _Nonnull handler);

void nm_path_monitor_start(nw_path_monitor_t _Nonnull monitor);
void nm_path_monitor_cancel(nw_path_monitor_t _Nonnull monitor);

// ============================================================
// Network interface enumeration (getifaddrs) — ICE / WebRTC host candidates
// ============================================================

// Per-address callback, invoked synchronously once per getifaddrs record:
//   name: BSD interface name (e.g. "en0"); index: OS interface index;
//   is_up / is_loopback: interface flags as 0/1; address: numeric IP literal
//   (NI_NUMERICHOST), or nil when the record carries no address.
typedef void (^nm_iface_block)(
    NSString * _Nullable name,
    uint32_t index,
    int32_t is_up,
    int32_t is_loopback,
    NSString * _Nullable address);

// Enumerates every local interface address via getifaddrs, invoking [cb] once per
// (interface, address) record. A no-op if getifaddrs fails.
void nm_enumerate_interfaces(nm_iface_block _Nonnull cb);

#endif /* NM_NETWORK_HELPERS_H */
