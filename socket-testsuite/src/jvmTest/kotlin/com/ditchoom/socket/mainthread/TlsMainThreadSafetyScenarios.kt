package com.ditchoom.socket.mainthread

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * TCP over TLS from the main-like thread. The peer is a JDK [SSLServerSocket] on its own thread (it is
 * test scaffolding, not the API under test). Two shapes: a full round trip with verification off, and
 * a connect with the default, verifying config — which must fail on the self-signed peer, but only
 * after loading the platform trust store, which is the part that must not block. Each is its own test
 * class per client implementation, so each is the first TLS use in its JVM.
 */
abstract class TlsMainThreadSafetyScenarios(
    private val client: JvmClientImplementation,
    private val verifying: Boolean,
) {
    @Test
    fun connectWriteReadClose() =
        client.selected {
            LoopbackTlsEchoServer().use { server ->
                runOnMainLike {
                    if (verifying) {
                        assertFails {
                            ClientSocket.connect(server.port, "localhost", TransportConfig.tlsDefault()) { socket ->
                                socket.writeString("hello")
                                socket.readString()
                            }
                        }
                        assertOnMainLike("a failed TLS connect")
                    } else {
                        ClientSocket.connect(server.port, "localhost", TransportConfig.tlsInsecure()) { socket ->
                            assertOnMainLike("TLS connect")
                            socket.writeString("hello")
                            assertEquals("echo:hello", socket.readString())
                            assertOnMainLike("TLS read")
                        }
                        assertOnMainLike("TLS close")
                    }
                }
            }
        }

    private class LoopbackTlsEchoServer : AutoCloseable {
        private val dir: File = Files.createTempDirectory("main-like-tls").toFile()
        private val socket = serverContext(dir).serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val port: Int = socket.localPort

        init {
            thread(isDaemon = true, name = "loopback-tls-echo") {
                while (!socket.isClosed) {
                    val peer = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            peer.use {
                                val buffer = ByteArray(5)
                                val n = it.getInputStream().read(buffer)
                                it.getOutputStream().apply {
                                    write("echo:".toByteArray() + buffer.copyOf(n))
                                    flush()
                                }
                                // Hold the connection until the client closes, so the client reads the echo.
                                it.getInputStream().read()
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            dir.deleteRecursively()
        }

        private companion object {
            const val PASSWORD = "loopback"

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
                        "CN=localhost",
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
                // An explicit (empty) trust-manager list, not null: null would load the platform trust
                // store here, on the test thread, and warm the very cache the client must load itself.
                return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, arrayOf(), null) }
            }
        }
    }
}

class TlsAsyncChannelMainThreadSafetyTest : TlsMainThreadSafetyScenarios(JvmClientImplementation.AsyncChannel, verifying = false)

class TlsNioSelectorMainThreadSafetyTest : TlsMainThreadSafetyScenarios(JvmClientImplementation.NioSelector, verifying = false)

class TlsNioBlockingMainThreadSafetyTest : TlsMainThreadSafetyScenarios(JvmClientImplementation.NioBlocking, verifying = false)

class TlsVerifyingMainThreadSafetyTest : TlsMainThreadSafetyScenarios(JvmClientImplementation.AsyncChannel, verifying = true)
