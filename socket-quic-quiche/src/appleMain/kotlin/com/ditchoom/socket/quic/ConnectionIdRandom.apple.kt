@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.arc4random_buf
import kotlin.random.Random

/**
 * Apple: `arc4random_buf`, the system's CSPRNG. It is thread-safe, never blocks and cannot fail, so this
 * source never throws [EntropyUnavailableException].
 */
internal class Arc4Random : NativeEntropyRandom() {
    override fun nextLong(): Long =
        memScoped {
            val drawn = alloc<LongVar>()
            arc4random_buf(drawn.ptr, sizeOf<LongVar>().convert())
            drawn.value
        }
}

internal actual val connectionIdRandom: Random = Arc4Random()
