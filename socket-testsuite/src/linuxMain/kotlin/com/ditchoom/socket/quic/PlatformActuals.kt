@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv
import kotlin.time.Duration

actual fun isAppleKNative(): Boolean = false

actual fun readHarnessCaCertPem(path: String): String? =
    memScoped {
        val file = fopen(path, "r") ?: return@memScoped null
        try {
            val chunkSize = 65536
            val chunk = allocArray<ByteVar>(chunkSize)
            var bytes = ByteArray(0)
            while (true) {
                val n = fread(chunk, 1.convert(), chunkSize.convert(), file).toInt()
                if (n <= 0) break
                bytes += chunk.readBytes(n)
            }
            bytes.decodeToString().trim()
        } finally {
            fclose(file)
        }
    }

actual fun isKotlinNative(): Boolean = true

actual fun quicHarnessAvailability(): QuicHarnessAvailability = QuicHarnessAvailability.Available

actual fun armStallDump(
    after: Duration,
    label: String,
): StallDump {
    // Kotlin/Native exposes no other thread's stack: there is nothing to dump.
    return StallDump { }
}

internal actual fun traceRingRequested(): Boolean = getenv("QUIC_TEST_TRACE_RING")?.toKString() == "1"
