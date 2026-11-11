package com.ditchoom.socket.mainthread

import java.io.File
import java.io.FileInputStream
import java.security.Provider
import java.security.SecureRandomSpi
import java.security.Security

/**
 * A `SecureRandom` that reads the OS entropy device on EVERY draw.
 *
 * The JDK's default on Linux and macOS, `NativePRNG`, keeps a small buffer of `/dev/urandom` bytes and
 * only reads the device again once that buffer is spent or more than 100 ms old. So whether a draw on
 * the caller's thread blocks depends on how long ago some other thread last drew: a fast machine draws
 * while the buffer is fresh and passes, a loaded CI runner reads the device and fails (PR #806's first
 * Linux run: `SSLEngine.beginHandshake` on the main-like thread). Installed first, this makes every draw
 * a device read, so a draw on the main-like thread fails every time, on every machine.
 */
internal class UnbufferedDeviceRandom : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray) = Unit

    override fun engineNextBytes(bytes: ByteArray) {
        FileInputStream(DEVICE).use { input ->
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                check(n > 0) { "$DEVICE ended" }
                read += n
            }
        }
    }

    override fun engineGenerateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also(::engineNextBytes)

    internal companion object {
        const val DEVICE = "/dev/urandom"

        private object UnbufferedDeviceRandomProvider : Provider(
            "MainLikeUnbufferedDeviceRandom",
            "1.0",
            "SecureRandom that reads $DEVICE on every draw (main-thread safety tests only)",
        ) {
            init {
                putService(Service(this, "SecureRandom", "NativePRNG", UnbufferedDeviceRandom::class.java.name, null, null))
            }
        }

        /** Makes [UnbufferedDeviceRandom] what `new SecureRandom()` returns in this JVM. */
        fun installFirst() {
            if (!File(DEVICE).exists()) return
            Security.insertProviderAt(UnbufferedDeviceRandomProvider, 1)
        }
    }
}
