package com.ditchoom.socket.udp

/**
 * The native [HostLoopback], per family rather than shared: Linux must `bind` through the module's
 * cinterop wrapper (glibc's `bind` takes a transparent union K/N cannot express) while Apple calls
 * `platform.posix.bind` directly, and the two `sockaddr_in` layouts differ in their first two bytes —
 * BSD carries `sin_len` where Linux starts a 2-byte host-order family. Those are exactly the details a
 * raw probe must not get from the code it is checking, so each actual writes its own.
 */
internal expect val hostLoopback: HostLoopback
