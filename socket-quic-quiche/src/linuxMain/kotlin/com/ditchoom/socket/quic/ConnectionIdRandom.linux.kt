@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.errno
import platform.posix.open
import platform.posix.read
import kotlin.random.Random

/**
 * Linux: `/dev/urandom`, read with `read(2)` on one descriptor opened once. `getrandom(2)` would do the same
 * without a descriptor, but the Kotlin/Native x64 sysroot is glibc 2.19, which declares neither it nor
 * `getentropy`. A `read` on a shared descriptor is atomic per call, so concurrent draws never share bytes.
 */
internal class DevUrandomRandom : NativeEntropyRandom() {
    private val descriptor: Int = openUrandom()

    override fun nextLong(): Long =
        memScoped {
            val drawn = alloc<LongVar>()
            val wanted = sizeOf<LongVar>()
            var have = 0L
            while (have < wanted) {
                val n = read(descriptor, drawn.ptr.reinterpret<ByteVar>() + have, (wanted - have).convert())
                when {
                    n > 0 -> have += n
                    n == 0L -> throw EntropyUnavailableException(EntropyFailure.ReadTruncated, 0)
                    errno != EINTR -> throw EntropyUnavailableException(EntropyFailure.ReadFailed, errno)
                }
            }
            drawn.value
        }

    private fun openUrandom(): Int {
        val fd = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
        if (fd < 0) throw EntropyUnavailableException(EntropyFailure.Unopenable, errno)
        return fd
    }
}

internal actual val connectionIdRandom: Random = DevUrandomRandom()
