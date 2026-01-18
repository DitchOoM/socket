package com.ditchoom.socket

import com.ditchoom.buffer.DataBuffer
import com.ditchoom.buffer.MutableDataBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Converts a ReadBuffer to NSData for zero-copy network operations.
 *
 * For buffers backed by NSData (DataBuffer, MutableDataBuffer), this returns
 * the underlying data directly without copying. For other buffer types, a copy is made.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, UnsafeNumber::class)
internal fun ReadBuffer.toNSData(): NSData {
    // Check for zero-copy buffer types that have direct NSData access
    return when (this) {
        is MutableDataBuffer -> data // NSMutableData extends NSData
        is DataBuffer -> data // Direct NSData access
        else -> {
            // Fallback: copy bytes to NSData for other buffer types
            val remaining = remaining()
            if (remaining == 0) {
                NSData()
            } else {
                val bytes = readByteArray(remaining)
                bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
                }
            }
        }
    }
}
