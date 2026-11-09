package com.ditchoom.socket.mainthread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Proves the check is real before any scenario is believed: a blocking call made on the main-like
 * thread FAILS the run, for each kind of call the scenarios rely on it catching, while the same call
 * moved to Dispatchers.IO passes. Without this a BlockHound that silently failed to install (an agent
 * refused by the JVM, a missing flag) would turn every scenario green.
 */
class MainThreadSafetyHarnessProbeTest {
    @Test
    fun sleepingOnTheMainLikeThreadFails() = assertCaught("java.lang.Thread.sleep") { Thread.sleep(1) }

    @Test
    fun aFileWriteOnTheMainLikeThreadFails() {
        val file = java.io.File.createTempFile("main-like", ".probe")
        try {
            val stream = file.outputStream()
            stream.use { assertCaught("java.io.FileOutputStream") { it.write(1) } }
        } finally {
            file.delete()
        }
    }

    @Test
    fun aDnsLookupOnTheMainLikeThreadFails() = assertCaught("lookupAllHostAddr") { InetAddress.getAllByName("localhost") }

    @Test
    fun aNativeLibraryLoadOnTheMainLikeThreadFails() =
        assertCaught("NativeLibraries") {
            // Any library the JDK has not loaded yet: the dlopen is what must be caught, not its symbols.
            System.load(java.io.File(System.getProperty("java.home"), "lib/" + System.mapLibraryName("j2pkcs11")).path)
        }

    @Test
    fun aSecureRandomDrawOnTheMainLikeThreadFails() =
        // A draw that NativePRNG would usually serve from its buffer: the harness makes every draw a
        // device read, so this fails however recently something else drew.
        assertCaught("java.io.FileInputStream") {
            java.security.SecureRandom().nextBytes(ByteArray(32))
            java.security.SecureRandom().nextBytes(ByteArray(32))
        }

    @Test
    fun theSameCallsOffTheMainLikeThreadPass() =
        runOnMainLike {
            withContext(Dispatchers.IO) {
                java.security.SecureRandom().nextBytes(ByteArray(32))
                // Not a DNS lookup: the JDK caches the answer, which would leave the DNS probe above
                // nothing to catch if this ran first.
                Thread.sleep(1)
            }
            assertOnMainLike("withContext(Dispatchers.IO)")
        }

    private fun assertCaught(
        expected: String,
        blocking: () -> Unit,
    ) {
        val failure = assertFailsWith<AssertionError> { runOnMainLike { blocking() } }
        assertContains(failure.message.orEmpty(), expected)
    }
}
