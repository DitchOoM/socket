package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.toNativeData
import java.security.MessageDigest

private const val SHA256_DIGEST_BYTES = 32

/**
 * JVM/Android SHA-256 straight from JCA [MessageDigest]: a pure-Java path with no native library, so there is
 * no BoringSSL to collide with quiche's (unlike Linux; see [sha256Into]'s expect doc and the linuxMain actual).
 *
 * Deliberately not `buffer-crypto`: its Android artifact declares minSdk 28, which would fail manifest merging
 * for every app below that, while this module supports minSdk 24. `MessageDigest` is available on all of them.
 *
 * Non-consuming on [input]: a heap buffer is read through its own backing array, a direct one through a
 * read-only `ByteBuffer` view ([toNativeData] leaves position and limit alone). The digest goes straight into
 * [dest]'s backing array when it has one.
 */
internal actual fun sha256Into(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= SHA256_DIGEST_BYTES) {
        "dest needs $SHA256_DIGEST_BYTES bytes remaining, has ${dest.remaining()}"
    }
    val md = MessageDigest.getInstance("SHA-256")
    if (input.remaining() > 0) {
        val managed = input.managedMemoryAccess
        if (managed != null) {
            md.update(managed.backingArray, managed.arrayOffset + input.position(), input.remaining())
        } else {
            md.update(input.toNativeData().byteBuffer)
        }
    }
    val managedDest = dest.managedMemoryAccess
    if (managedDest != null) {
        val pos = dest.position()
        md.digest(managedDest.backingArray, managedDest.arrayOffset + pos, SHA256_DIGEST_BYTES)
        dest.position(pos + SHA256_DIGEST_BYTES)
    } else {
        @Suppress("NoByteArrayInProd") // JCA MessageDigest.digest() returns the digest as byte[] for a direct dest
        val digest = md.digest()
        dest.writeBytes(digest)
    }
}
