package com.ditchoom.socket.quic

import platform.Foundation.NSData

/**
 * Foundation helpers whose implementations touch `NSUInteger` (an `NSData.length` read, the
 * base64 decoding-options flag). `NSUInteger` has a platform-dependent bit width (64-bit on
 * macOS/iOS/tvOS, 32-bit on `watchosArm64`/arm64_32), and this module's Objective-C
 * `NWQuicHelpers` cinterop (`-framework Foundation`) de-commonizes `platform.Foundation`'s
 * numeric typealiases — so `compileAppleMainKotlinMetadata` rejects any `NSUInteger` value in
 * the shared `appleMain` source set ("numbers with different bit widths").
 *
 * Keeping these declarations as `expect` here (signatures use only width-stable `Int`/`NSData`/
 * `String`) with per-target `actual`s under `src/appleNativeImpl` means the `NSUInteger`-touching
 * code is only ever compiled per-target, never to commonized metadata — mirroring the root
 * `:socket` module's `appleNativeImpl` arrangement.
 */
internal expect fun nsDataLengthInt(data: NSData): Int

/** Decode a base64 string (ignoring unknown characters) straight into DER-backed [NSData]. */
internal expect fun decodeBase64ToNSData(base64: String): NSData?

/**
 * True on macOS 26 / iOS 26 / tvOS 26 / watchOS 26 or later — where the Swift `NetworkConnection<QUIC>`
 * API (and thus the [connectQuicSwift] backend) is available. `NSProcessInfo.operatingSystemVersion`
 * reads `NSInteger` (platform-width), so this stays a per-target actual like [nsDataLengthInt].
 */
internal expect fun isAppleOS26OrLater(): Boolean
