package com.ditchoom.socket.quic

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import kotlin.random.Random

/**
 * A [Random] over one shared [SecureRandom]. `SecureRandom` synchronises itself, so any thread may draw.
 *
 * Built the way `JvmTlsHandler.seededRandom` builds the TLS engine's: the JDK's `DRBG` (NIST SP 800-90A)
 * reads its seed once, when first used, and is then pure computation, where the default `NativePRNG`
 * re-reads `/dev/urandom` whenever its buffer is spent or 100 ms old, a file read on whichever thread
 * draws (here the server's receive loop). Android has no `DRBG`; its default is Conscrypt over BoringSSL's
 * `RAND_bytes`, which does no file I/O. The first draw, which seeds, is made when this is created.
 */
internal class SecureJvmRandom(
    private val secure: SecureRandom = newSecureRandom(),
) : Random() {
    override fun nextBits(bitCount: Int): Int = secure.nextInt() ushr (32 - bitCount)

    override fun nextInt(): Int = secure.nextInt()

    override fun nextLong(): Long = secure.nextLong()

    private companion object {
        fun newSecureRandom(): SecureRandom {
            val random =
                try {
                    SecureRandom.getInstance("DRBG")
                } catch (e: NoSuchAlgorithmException) {
                    SecureRandom()
                }
            random.nextLong()
            return random
        }
    }
}

internal actual val connectionIdRandom: Random = SecureJvmRandom()
