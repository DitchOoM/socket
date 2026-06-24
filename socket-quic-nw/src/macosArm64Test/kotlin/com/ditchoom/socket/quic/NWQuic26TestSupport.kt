@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.nwquic26.NWQuic26Conn
import com.ditchoom.socket.quic.nwquic26.NWQuic26Stream
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.memcpy
import kotlin.coroutines.resume

// Shared helpers for the macosArm64Test NWQuic26 bridge tests. These live here (not in appleTest's
// AppleQuicTestSupport) because the NWQuic26 cinterop bindings are per-target — macosArm64Test is the
// only source set that can see them. K/N imports `_Nonnull` ObjC block params as nullable; the bridge
// never delivers null for them, so the `!!` below are safe.

internal fun testCertPath(name: String): String =
    listOf("testcerts/$name", "socket-quic-nw/testcerts/$name")
        .firstOrNull { access(it, F_OK) == 0 }
        ?: error("Test cert not found: $name (did generateTestP12 run?)")

internal fun readFileText(path: String): String =
    memScoped {
        val fp = fopen(path, "r") ?: error("Cannot open $path")
        try {
            val sb = StringBuilder()
            val bufSize = 4096
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val n = fread(buf, 1.convert(), (bufSize - 1).convert(), fp).toInt()
                if (n <= 0) break
                buf[n] = 0
                sb.append(buf.toKString())
            }
            sb.toString()
        } finally {
            fclose(fp)
        }
    }

internal fun String.hexToByteArray(): ByteArray =
    ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.convert()) }
    }

internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/** The SHA-256 leaf-hash pin for a named W3C fixture (`pinned` etc.), as the 32-byte [NSData] the bridge expects. */
internal fun pinFor(name: String): NSData = readFileText(testCertPath("$name.sha256")).trim().hexToByteArray().toNSData()

/** Open a local stream and await its real wire id; throws on failure. */
internal suspend fun NWQuic26Conn.openStreamAwait(uni: Boolean): Pair<NWQuic26Stream, Long> =
    suspendCancellableCoroutine { cont ->
        openStreamWithUni(uni) { stream, id, errCode, desc ->
            if (stream != null && errCode == 0) {
                cont.resume(stream to id.toLong())
            } else {
                cont.resumeWith(Result.failure(IllegalStateException("openStream failed: $errCode ${desc ?: ""}")))
            }
        }
    }

/** Write [bytes] to the stream, optionally FINishing the send side; throws on send error. */
internal suspend fun NWQuic26Stream.sendAwait(
    bytes: ByteArray,
    endOfStream: Boolean,
): Unit =
    suspendCancellableCoroutine { cont ->
        send(bytes.toNSData(), endOfStream) { errCode, _, desc ->
            if (errCode == 0) {
                cont.resume(Unit)
            } else {
                cont.resumeWith(Result.failure(IllegalStateException("stream send failed: $errCode ${desc ?: ""}")))
            }
        }
    }

/** One-shot stream receive → (bytes, isEnd, resetCode). resetCode == ULong.MAX_VALUE means "no reset". */
internal suspend fun NWQuic26Stream.receiveOnce(maxBytes: Int = 65_536): Triple<ByteArray?, Boolean, ULong> =
    suspendCancellableCoroutine { cont ->
        receiveWithMaxBytes(maxBytes) { data, isEnd, resetCode, _, _ ->
            cont.resume(Triple(data?.toByteArray(), isEnd, resetCode))
        }
    }

/** Read the whole stream (looping [receiveOnce]) until end-of-stream, returning the UTF-8 payload. */
internal suspend fun NWQuic26Stream.readAllString(): String {
    val sb = StringBuilder()
    while (true) {
        val (bytes, isEnd, _) = receiveOnce()
        if (bytes != null && bytes.isNotEmpty()) sb.append(bytes.decodeToString())
        if (isEnd) break
    }
    return sb.toString()
}
