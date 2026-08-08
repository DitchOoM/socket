@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import platform.posix.AF_INET
import platform.posix.AF_INET6

// The slim, width-independent half of this module's Apple surface — deliberately the ONLY thing left in
// `appleMain`.
//
// Everything else Apple-specific lives in `src/appleNativeImpl/kotlin`, added per-target via `srcDir`
// (see this module's build.gradle.kts). The reason is watchosArm64: it is arm64_32, so `size_t`,
// `ssize_t` and `NSUInteger` are 32-bit there and 64-bit on every other Apple target. A shared
// `appleMain` forces a `compileAppleMainKotlinMetadata` compilation that must type-check one source
// against all of them at once, and the datagram channels' `memcpy` lengths, `sizeOf<…>().convert()` and
// `content.length` are exactly the "numbers with different bit widths" that compilation rejects — even
// though each individual target compiles fine.
//
// These two declarations survive here because they carry no platform-width types, and because
// `:socket-quic-quiche`'s OWN `appleMain` consumes them: that module's shared Apple source needs them
// resolvable from metadata, which per-target `srcDir` output is not. Keep this file free of
// `size_t`/`ssize_t`/`NSUInteger`-derived types, or the whole arrangement collapses back into #280.

/**
 * The BSD/Darwin C `sockaddr` layout for [SocketAddressCodec]: a length byte, a single-byte
 * `sa_family`, `AF_INET6` = 30.
 */
@ExperimentalDatagramApi
val appleSockAddrLayout: SockAddrLayout = SockAddrLayout(hasLenByte = true, afInet = AF_INET, afInet6 = AF_INET6)

/**
 * A `connect()` fault surfaced by the NWConnection state handler (terminal failed/cancelled). Public so
 * a consumer in another module (e.g. `:socket-quic-quiche`'s Apple datapath) can catch and map it.
 */
class UdpConnectException(
    message: String,
) : RuntimeException(message)
