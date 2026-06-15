@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.socket.quic

import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingIgnoreUnknownCharacters
import platform.Foundation.create
import platform.Foundation.length

// Per-target actuals (compiled once per Apple target via the appleNativeImpl srcDir, so the
// platform-width NSUInteger reads resolve to a concrete width and never reach commonized
// appleMain metadata). See FoundationInterop.kt for why.

internal actual fun nsDataLengthInt(data: NSData): Int = data.length.toInt()

internal actual fun decodeBase64ToNSData(base64: String): NSData? =
    NSData.create(
        base64EncodedString = base64,
        options = NSDataBase64DecodingIgnoreUnknownCharacters,
    )
