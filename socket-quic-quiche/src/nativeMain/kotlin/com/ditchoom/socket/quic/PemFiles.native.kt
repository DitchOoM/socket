@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.unlink
import platform.posix.write

/**
 * [PeerCertificateBackend.withPemFiles] for Apple and Linux: `mkstemp` files (mode 0600) under `$TMPDIR`
 * (the per-app temp directory on iOS, where `/tmp` is not writable), falling back to `/tmp`, unlinked when
 * [block] returns.
 */
internal suspend fun <R> withPosixPemFiles(
    certificatePem: ReadBuffer,
    privateKeyPem: ReadBuffer,
    block: suspend (QuicTlsConfig) -> R,
): R {
    val certificate = writeOwnerOnlyTempFile("ditchoom-peer-cert", certificatePem)
    try {
        val key = writeOwnerOnlyTempFile("ditchoom-peer-key", privateKeyPem)
        try {
            return block(QuicTlsConfig(certChainPath = certificate, privKeyPath = key))
        } finally {
            unlink(key)
        }
    } finally {
        unlink(certificate)
    }
}

private fun writeOwnerOnlyTempFile(
    prefix: String,
    content: ReadBuffer,
): String =
    memScoped {
        val dir = getenv("TMPDIR")?.toKString()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val template = "$dir/$prefix-XXXXXX"
        val path = allocArray<ByteVar>(template.length + 1)
        for (i in template.indices) path[i] = template[i].code.toByte()
        path[template.length] = 0
        val fd = mkstemp(path)
        if (fd < 0) throw PeerCertificateException(PeerCertificateFailure.PemFilesFailed("mkstemp($template) errno=$errno"))
        val created = path.toKString()
        val failedErrno =
            try {
                writeFully(fd, content)
            } finally {
                close(fd)
            }
        if (failedErrno != 0) {
            unlink(created)
            throw PeerCertificateException(PeerCertificateFailure.PemFilesFailed("write($created) errno=$failedErrno"))
        }
        created
    }

/** Write all of [content]'s remaining bytes to [fd] from its native memory; 0 on success, else the errno. */
private fun writeFully(
    fd: Int,
    content: ReadBuffer,
): Int {
    val start = content.nativeMemoryAccess!!.nativeAddress + content.position()
    val total = content.remaining()
    var offset = 0
    while (offset < total) {
        val written = write(fd, (start + offset).toCPointer<ByteVar>(), (total - offset).convert())
        if (written < 0) {
            if (errno == EINTR) continue
            return errno
        }
        offset += written.toInt()
    }
    return 0
}
