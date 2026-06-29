package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.sha256

/**
 * JVM/Android SHA-256 via `com.ditchoom.buffer.crypto.sha256`, which is backed by JCA `MessageDigest` on
 * these platforms — a pure-Java path with no native library, so there is no BoringSSL to collide with
 * quiche's (unlike Linux; see [sha256Into]'s expect doc and the linuxMain actual). Non-consuming on [input].
 */
internal actual fun sha256Into(
    input: ReadBuffer,
    dest: WriteBuffer,
) = sha256(input, dest)
