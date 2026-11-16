@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.unlink

/**
 * The caller's pinned CAs, through a temporary file (quiche loads anchors only from one). Kotlin/Native loads no
 * platform default store here: a client that verifies its peer without pinned CAs is checked by the platform's own
 * trust where the 4.x engine does so, which the core does not do yet. A bundle quiche refuses leaves the store empty,
 * and the handshake then fails verification: that is the failure the caller sees.
 */
internal actual fun loadTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: BufferFactory,
) {
    val pems = quicOptions.trustedCaCertificatesPem
    if (pems.isEmpty()) return
    when (val written = writePemBundle(pems.joinToString("\n"))) {
        is PemBundle.Written -> {
            val path = bufferFactory.allocate(written.path.length + 1)
            path.writeString(written.path, Charset.UTF8)
            path.writeByte(0)
            path.resetForRead()
            when (val access = path.nativeMemoryAccess) {
                null -> Unit
                else -> api.configLoadVerifyLocationsFromFile(config, access.nativeAddress.toLong())
            }
            path.freeNativeMemory()
            unlink(written.path)
        }
        is PemBundle.NotWritten -> Unit
    }
}

private sealed interface PemBundle {
    class Written(
        val path: String,
    ) : PemBundle

    data object NotWritten : PemBundle
}

/** `$TMPDIR` first: iOS sandboxes `/tmp`, and macOS sets `TMPDIR` too. */
private fun writePemBundle(bundle: String): PemBundle =
    memScoped {
        val dir = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
        val template = "$dir/ditchoom-quic-ca-XXXXXX"
        val path = allocArray<ByteVar>(template.length + 1)
        for (i in template.indices) path[i] = template[i].code.toByte()
        path[template.length] = 0
        val fd = mkstemp(path)
        if (fd < 0) return@memScoped PemBundle.NotWritten
        val file = fdopen(fd, "w")
        if (file == null) {
            close(fd)
            return@memScoped PemBundle.NotWritten
        }
        fputs(bundle, file)
        fclose(file)
        PemBundle.Written(path.toKString())
    }
