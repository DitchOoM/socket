package com.ditchoom.socket

import com.ditchoom.buffer.MutableDataBuffer
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.create
import platform.Foundation.subdataWithRange

/**
 * Converts the remaining bytes of a ReadBuffer (`[position(), limit())`) to NSData for
 * network writes.
 *
 * The zero-copy paths for `MutableDataBuffer` / `NSDataBuffer` must honour position and
 * limit — returning the raw backing NSData was a bug that leaked unrelated prefix/suffix
 * bytes onto the wire whenever the caller had positioned the buffer inside a larger
 * allocation (the websocket codec's reserved-prefix pattern is the motivating case).
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, UnsafeNumber::class)
internal fun ReadBuffer.toNSData(): NSData =
    when (this) {
        is MutableDataBuffer -> {
            val pos = position()
            val rem = remaining()
            if (pos == 0 && rem == data.length.toInt()) {
                data
            } else {
                data.subdataWithRange(NSMakeRange(pos.convert(), rem.convert()))
            }
        }
        is NSDataBuffer -> {
            val pos = position()
            val rem = remaining()
            if (pos == 0 && rem == data.length.toInt()) {
                data
            } else {
                data.subdataWithRange(NSMakeRange(pos.convert(), rem.convert()))
            }
        }
        else -> {
            // Fallback: copy bytes to NSData for other buffer types. Preserve position — the
            // caller (NWSocketWrapper.write) is responsible for advancing it after the write
            // completes, matching the zero-copy branches above.
            val remaining = remaining()
            if (remaining == 0) {
                NSData()
            } else {
                val savedPosition = position()
                val bytes = readByteArray(remaining)
                position(savedPosition)
                bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
                }
            }
        }
    }
