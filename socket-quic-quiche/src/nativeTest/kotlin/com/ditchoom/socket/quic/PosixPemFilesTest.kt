@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Apple/Linux PEM hand-off: regular files, mode 0600, present during the bind and gone after it. */
class PosixPemFilesTest {
    @Test
    fun pemFilesAreOwnerOnlyAndDeletedAfterTheBind() {
        val engine = peerCertificates as? PeerCertificateSupport.Available ?: fail("peerCertificates=$peerCertificates")
        engine.generate().use { certificate ->
            val seen = mutableListOf<String>()
            val inspecting =
                FilesOnlyEngine { tls ->
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
                    inspecting.bind(QuicPortBinding.Own(0, null), certificate, QuicOptions(alpnProtocols = listOf("test")), 1.seconds)
                }
            }
            assertEquals(2, seen.size, "the engine was never handed the PEM files")
            for (path in seen) assertNotEquals(0, access(path, F_OK), "$path survived the bind")
        }
    }

    /** Runs [inspect] on the files [QuicEngine.bind] is handed, then aborts the bind. */
    private class FilesOnlyEngine(
        private val inspect: (QuicTlsConfig) -> Unit,
    ) : QuicEngine {
        override val capabilities = EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = true)

        override suspend fun connect(
            binding: QuicClientBinding,
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            transport: TransportConfig,
            timeout: Duration,
        ): QuicConnection = throw UnsupportedOperationException("bind-only test engine")

        override suspend fun bind(
            binding: QuicPortBinding,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: Duration,
        ): QuicServer {
            inspect(tlsConfig)
            throw BindInspected()
        }
    }

    private class BindInspected : Exception()
}
