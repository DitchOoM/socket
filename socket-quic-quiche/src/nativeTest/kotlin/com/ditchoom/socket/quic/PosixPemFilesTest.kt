@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import platform.posix.F_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.fail

/** The Apple/Linux PEM hand-off: regular files, mode 0600, present during the bind and gone after it. */
class PosixPemFilesTest {
    @Test
    fun pemFilesAreOwnerOnlyAndDeletedAfterTheBind() =
        runTest {
            val engine = peerCertificates as? PeerCertificateSupport.Available ?: fail("peerCertificates=$peerCertificates")
            engine.mint().use { certificate ->
                val seen = mutableListOf<String>()
                val inspecting: (QuicTlsConfig) -> Unit =
                    { tls ->
                        for (path in listOf(tls.certChainPath, tls.privKeyPath)) {
                            seen += path
                            memScoped {
                                val info = alloc<stat>()
                                assertEquals(0, stat(path, info.ptr), "stat($path)")
                                assertEquals(S_IFREG.toInt(), info.st_mode.toInt() and S_IFMT.toInt(), "$path is not a regular file")
                                assertEquals("600", (info.st_mode.toInt() and 0x1FF).toString(8), "mode of $path")
                                assertNotEquals(0L, info.st_size.toLong(), "$path is empty")
                            }
                        }
                    }
                runBlocking {
                    assertFailsWith<BindInspected> {
                        certificate.withPemFiles { tls ->
                            inspecting(tls)
                            throw BindInspected()
                        }
                    }
                }
                assertEquals(2, seen.size, "the engine was never handed the PEM files")
                for (path in seen) assertNotEquals(0, access(path, F_OK), "$path survived the bind")
            }
        }

    private class BindInspected : Exception()
}
