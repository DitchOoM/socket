#ifndef NM_PATH_MONITOR_H
#define NM_PATH_MONITOR_H

// NWPathMonitor bridge for :network-monitor's AppleNetworkMonitor — the path-monitor half of what used
// to be :socket's nw_helpers.h, and NOTHING else.
//
// `nw_path_monitor_set_update_handler` takes an Objective-C block whose argument is an `nw_path_t`, and
// the accessors that make a path useful (`nw_path_enumerate_interfaces`, `nw_path_uses_interface_type`)
// are themselves block-taking or C-enum-returning. Kotlin/Native can neither define that block nor call
// those accessors directly, so the whole path→scalars projection happens here in C and only
// int32_t/uint32_t/NSString* cross back into Kotlin — the same shape :socket used, moved rather than
// redesigned.
//
// This is a SEPARATE cinterop from :socket's NWHelpers, deliberately: with both modules on one
// classpath there must be exactly one declarer of each helper. :socket dropped these four functions and
// the handler typedef when AppleNetworkMonitor moved here; it keeps everything else (TCP/TLS/WebSocket/
// listener plus `nw_helper_enumerate_interfaces`, still used by its own `enumerateNetworkInterfaces`).
// Re-declaring <Network/Network.h> from two cinterops is fine — the framework headers are not ours, and
// two cinterops declaring them was measured to compile and link (see network-monitor/build.gradle.kts);
// it is duplicate declarations of OUR OWN helpers that must not happen.

#include <Network/Network.h>
#include <Foundation/Foundation.h>

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

#endif /* NM_PATH_MONITOR_H */
