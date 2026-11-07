package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.data.readBuffer
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A TLS [ClientSocket.write] reports the PLAINTEXT bytes it consumed from the caller's buffer, as the
 * `ByteSink` contract requires — never the size of the ciphertext it put on the wire. `writeFully`
 * resumes from `position-before-write + count`, so a count that includes the TLS record overhead
 * moves the cursor past the buffer's limit (the write throws) or skips plaintext bytes (the peer
 * receives a corrupted stream).
 *
 * The peer is a JDK [SSLServerSocket] on loopback, so these run without the external TLS harness.
 */
open class JvmTlsWriteTests {
    @Test
    fun writeReportsPlaintextBytesConsumed() =
        runTestNoTimeSkipping {
            val payload = pattern(SMALL_WRITE)
            LoopbackTlsServer(expectedBytes = payload.size).use { server ->
                server.connectClient { socket ->
                    val buffer = payload.toReadBuffer()
                    val written = socket.write(buffer).count
                    assertEquals(payload.size, written, "a TLS write must report plaintext bytes, not ciphertext")
                    assertEquals(payload.size, buffer.position())
                }
                assertContentEquals(payload, server.received())
            }
        }

    @Test
    fun sequentialWritesDeliverEveryByteInOrder() =
        runTestNoTimeSkipping {
            val writes = listOf(pattern(SMALL_WRITE), pattern(7), pattern(NOT_A_RECORD_MULTIPLE), pattern(3))
            val expected = writes.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            LoopbackTlsServer(expectedBytes = expected.size).use { server ->
                server.connectClient { socket ->
                    writes.forEach { socket.writeFully(it.toReadBuffer()) }
                }
                assertContentEquals(expected, server.received())
            }
        }

    @Test
    fun partialWriteResumesFromTheReportedCount() =
        runTestNoTimeSkipping {
            val payload = pattern(NOT_A_RECORD_MULTIPLE)
            LoopbackTlsServer(expectedBytes = payload.size).use { server ->
                server.connectClient { socket ->
                    val buffer = payload.toReadBuffer()
                    val written = socket.write(buffer).count
                    assertEquals(buffer.position(), written, "the reported count must equal the cursor advance")
                    socket.writeFully(buffer)
                }
                assertContentEquals(payload, server.received())
            }
        }

    private class LoopbackTlsServer(
        expectedBytes: Int,
    ) : AutoCloseable {
        private val keyStoreDir: File = Files.createTempDirectory("jvm-tls-write").toFile()
        private val serverSocket: SSLServerSocket =
            serverContext(keyStoreDir).serverSocketFactory.createServerSocket(0) as SSLServerSocket
        private val receivedBytes = CompletableFuture<ByteArray>()
        val port: Int = serverSocket.localPort

        init {
            thread(isDaemon = true, name = "loopback-tls-server") {
                try {
                    serverSocket.accept().use { peer ->
                        receivedBytes.complete(peer.getInputStream().readNBytes(expectedBytes))
                        peer.getOutputStream().apply {
                            write(ACK)
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    receivedBytes.completeExceptionally(e)
                }
            }
        }

        fun received(): ByteArray = receivedBytes.get(10, TimeUnit.SECONDS)

        /**
         * Runs [writes] over a TLS client, then waits for the server's ack before closing: closing with
         * the server's unread bytes still queued makes Windows reset the connection, discarding data the
         * server has not read yet.
         */
        suspend fun connectClient(writes: suspend (ClientSocket) -> Unit) =
            ClientSocket.connect(port, LOOPBACK, TransportConfig.tlsInsecure()) { socket ->
                writes(socket)
                val ack = socket.readBuffer(10.seconds)
                assertEquals(ACK.toByte(), ack.readByte())
            }

        override fun close() {
            serverSocket.close()
            keyStoreDir.deleteRecursively()
        }

        private companion object {
            const val PASSWORD = "loopback"
            const val ACK = 1

            fun serverContext(dir: File): SSLContext {
                val keyStoreFile = File(dir, "server.p12")
                val keytool = File(System.getProperty("java.home"), "bin/keytool").path
                val process =
                    ProcessBuilder(
                        keytool,
                        "-genkeypair",
                        "-alias",
                        "server",
                        "-keyalg",
                        "EC",
                        "-groupname",
                        "secp256r1",
                        "-dname",
                        "CN=$LOOPBACK",
                        "-validity",
                        "1",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keyStoreFile.path,
                        "-storepass",
                        PASSWORD,
                        "-keypass",
                        PASSWORD,
                    ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "keytool failed: $output" }
                val keyStore = KeyStore.getInstance("PKCS12")
                keyStoreFile.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
                val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagers.init(keyStore, PASSWORD.toCharArray())
                return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
            }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val SMALL_WRITE = 5

        /** Larger than one 16 KiB TLS record and not a multiple of it. */
        const val NOT_A_RECORD_MULTIPLE = 50_001

        fun pattern(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

        fun ByteArray.toReadBuffer(): ReadBuffer = BufferFactory.Default.wrap(this)
    }
}

/** The same assertions over the NIO selector client, which has its own `write` entry into the TLS handler. */
class NioSelectorTlsWriteTests : JvmTlsWriteTests() {
    private var savedAsync = true
    private var savedBlocking = false

    @BeforeTest
    fun overrideClientMode() {
        savedAsync = useAsyncChannels
        savedBlocking = useNioBlocking
        useAsyncChannels = false
        useNioBlocking = false
    }

    @AfterTest
    fun restoreClientMode() {
        useAsyncChannels = savedAsync
        useNioBlocking = savedBlocking
    }
}
