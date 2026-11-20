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
 * The caller's pinned CAs, through a temporary file (quiche loads anchors only from one); with none pinned and quiche
 * checking the chain in the handshake, this platform's default anchors ([loadDefaultTrustAnchors]). A bundle quiche
 * refuses leaves the store empty, and the handshake then fails verification: that is the failure the caller sees.
 */
internal actual fun loadTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: BufferFactory,
) {
    val pems = quicOptions.trustedCaCertificatesPem
    if (pems.isNotEmpty()) {
        loadPemBundle(api, config, pems.joinToString("\n"), bufferFactory)
        return
    }
    if (!resolveVerifyPeer(quicOptions)) return
    when (platformChainTrust(quicOptions)) {
        PlatformChainTrust.InHandshake -> loadDefaultTrustAnchors(api, config, quicOptions, bufferFactory)
        is PlatformChainTrust.AfterHandshake -> Unit
    }
}

/** Loads the anchors this platform verifies against when the caller pinned none and quiche checks the chain. */
internal expect fun loadDefaultTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: BufferFactory,
)

/** Loads [bundle] (PEM) as anchors through a temporary file, removed again once quiche read it. */
internal fun loadPemBundle(
    api: QuicheApi,
    config: QuicheConfig,
    bundle: String,
    bufferFactory: BufferFactory,
) {
    when (val written = writePemBundle(bundle)) {
        is PemBundle.Written -> {
            loadAnchorFile(api, config, written.path, bufferFactory)
            unlink(written.path)
        }
        is PemBundle.NotWritten -> Unit
    }
}

/** Loads the PEM file at [path] as anchors. */
internal fun loadAnchorFile(
    api: QuicheApi,
    config: QuicheConfig,
    path: String,
    bufferFactory: BufferFactory,
) {
    withNativePath(path, bufferFactory) { api.configLoadVerifyLocationsFromFile(config, it) }
}

/** Loads the hashed certificate directory at [path] as anchors; quiche's return code, 0 on success. */
internal fun loadAnchorDirectory(
    api: QuicheApi,
    config: QuicheConfig,
    path: String,
    bufferFactory: BufferFactory,
): Int = withNativePath(path, bufferFactory) { api.configLoadVerifyLocationsFromDirectory(config, it) } ?: NO_NATIVE_MEMORY

/** Hands [use] the native address of [path] as a NUL-terminated string; null when [bufferFactory] has no native memory. */
private inline fun withNativePath(
    path: String,
    bufferFactory: BufferFactory,
    use: (Long) -> Int,
): Int? {
    val name = bufferFactory.allocate(path.length + 1)
    name.writeString(path, Charset.UTF8)
    name.writeByte(0)
    name.resetForRead()
    val result =
        when (val access = name.nativeMemoryAccess) {
            null -> null
            else -> use(access.nativeAddress.toLong())
        }
    name.freeNativeMemory()
    return result
}

private const val NO_NATIVE_MEMORY = -1

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
