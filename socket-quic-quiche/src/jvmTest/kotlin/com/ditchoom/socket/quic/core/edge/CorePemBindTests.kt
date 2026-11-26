package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.assertOwnerOnly
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A server whose certificate chain and key are PEM held in memory binds through files only it can read: they exist while
 * the bind loads them and are deleted as soon as the bind returns.
 */
class CorePemBindTests {
    private fun pem(path: String): ReadBuffer {
        val bytes = File(path).readBytes()
        val out = BufferFactory.network().allocate(bytes.size)
        out.writeBytes(bytes)
        out.resetForRead()
        return out
    }

    private fun identity(): PemIdentity {
        val tls = platformTlsConfig()
        return PemIdentity(pem(tls.certChainPath), pem(tls.privKeyPath), QuicSessionTicketKeys.PerServer)
    }

    /** The files this process's PEM hand-off leaves in the temp directory. */
    private fun leftovers(): List<String> =
        File(System.getProperty("java.io.tmpdir"))
            .list { _, name -> name.startsWith("ditchoom-peer-") }
            .orEmpty()
            .toList()

    @Test
    fun aServerBoundFromInMemoryPemServesAndItsFilesAreOwnerOnlyUntilTheBindReturns() =
        runBlocking {
            val before = leftovers()
            val seen = ArrayList<File>()
            val server =
                identity().onFiles { files ->
                    for (path in listOf(files.certChainPath, files.privKeyPath)) {
                        val file = File(path)
                        seen += file
                        assertTrue(file.isFile && file.length() > 0, "$path is a regular, non-empty file while the bind reads it")
                        assertOwnerOnly(file)
                    }
                    coreServe(
                        mismatch = MismatchResponse.Fail,
                        bind = loopbackV4(),
                        tls = files,
                        options = coreTestOptions,
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        readPolicy = ReadPolicy.Bounded(10.seconds),
                        writePolicy = WritePolicy.Bounded(10.seconds),
                    )
                }
            try {
                assertEquals(2, seen.size, "the bind was handed the chain and the key")
                for (file in seen) assertFalse(file.exists(), "${file.path} survived the bind")
                assertEquals(before, leftovers(), "the hand-off left nothing in the temp directory")
                val client = connectCore(server.port)
                client.connection.close(QuicError.NoError)
            } finally {
                server.close()
            }
        }

    @Test
    fun theEntryServesFromInMemoryPemAndLeavesNoFileBehind() =
        runBlocking {
            val before = leftovers()
            val served = serveOnTheCore(0, "127.0.0.1", identity(), coreTestOptions, BufferFactory.network())
            val serving = assertIs<QuicServe.Serving<CoreListener>>(served)
            try {
                assertEquals(before, leftovers(), "the bind deleted its files")
                val client = connectCore(serving.value.port)
                client.connection.close(QuicError.NoError)
            } finally {
                serving.value.close()
            }
        }
}
