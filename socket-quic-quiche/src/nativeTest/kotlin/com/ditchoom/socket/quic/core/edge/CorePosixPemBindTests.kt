@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.F_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** The Apple and Linux PEM hand-off: regular files, mode 0600, present while the bind reads them and gone after it. */
class CorePosixPemBindTests {
    private fun pem(path: String): ReadBuffer {
        val file = fopen(path, "rb") ?: error("cannot open $path")
        val bytes = ArrayList<Byte>()
        val chunk = ByteArray(4096)
        try {
            while (true) {
                val got = chunk.usePinned { fread(it.addressOf(0), 1.convert(), chunk.size.convert(), file).toInt() }
                if (got <= 0) break
                for (i in 0 until got) bytes += chunk[i]
            }
        } finally {
            fclose(file)
        }
        val out = BufferFactory.network().allocate(bytes.size)
        out.writeBytes(bytes.toByteArray())
        out.resetForRead()
        return out
    }

    private fun identity(): PemIdentity {
        val tls = platformTlsConfig()
        return PemIdentity(pem(tls.certChainPath), pem(tls.privKeyPath), QuicSessionTicketKeys.PerServer)
    }

    @Test
    fun theFilesAreOwnerOnlyWhileTheBindReadsThemAndDeletedAfterIt() =
        runBlocking {
            val seen = ArrayList<String>()
            identity().onFiles { files ->
                for (path in listOf(files.certChainPath, files.privKeyPath)) {
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
            assertEquals(2, seen.size, "the bind was handed the chain and the key")
            for (path in seen) assertNotEquals(0, access(path, F_OK), "$path survived the bind")
        }

    @Test
    fun theEntryServesFromInMemoryPem() =
        runBlocking {
            val served = serveOnTheCore(0, "127.0.0.1", identity(), coreTestOptions, BufferFactory.network())
            val serving = assertIs<QuicServe.Serving<CoreListener>>(served)
            try {
                val client = connectCore(serving.value.port)
                client.connection.close(QuicError.NoError)
            } finally {
                serving.value.close()
            }
        }
}
