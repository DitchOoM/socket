@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import platform.posix.AF_INET
import platform.posix.AF_INET6

// The slim, width-independent half of this module's Apple surface — deliberately the ONLY thing left in
// `appleMain`.
//
// Everything else Apple-specific lives in `src/appleNativeImpl/kotlin`, added per-target via `srcDir`
// (see this module's build.gradle.kts). A shared `appleMain` forces a
// `compileAppleMainKotlinMetadata` compilation that must type-check one source against every Apple
// target at once.
//
// These two declarations survive here because they carry no platform-width types, and because
// `:socket-quic-quiche`'s OWN `appleMain` consumes them: that module's shared Apple source needs them
// resolvable from metadata, which per-target `srcDir` output is not. Keep this file free of
// `size_t`/`ssize_t`/`NSUInteger`-derived types.

/**
 * The BSD/Darwin C `sockaddr` layout for [SocketAddressCodec]: a length byte, a single-byte
 * `sa_family`, `AF_INET6` = 30.
 */
@ExperimentalDatagramApi
val appleSockAddrLayout: SockAddrLayout = SockAddrLayout(hasLenByte = true, afInet = AF_INET, afInet6 = AF_INET6)
