package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Counts, per thread name, the sends and receives a loop's socket saw. */
internal class RecordingIo(
    private val real: DatagramBatchIo,
) : DatagramBatchIo by real {
    private val sends = ConcurrentHashMap<String, AtomicLong>()
    private val receives = ConcurrentHashMap<String, AtomicLong>()

    override fun send(batch: SendBatch): SendBatchResult {
        sends.computeIfAbsent(Thread.currentThread().name) { AtomicLong() }.incrementAndGet()
        return real.send(batch)
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        receives.computeIfAbsent(Thread.currentThread().name) { AtomicLong() }.incrementAndGet()
        return real.receive(into)
    }

    /** Sends by thread name, as they stand. */
    fun sendsByThread(): Map<String, Long> = sends.mapValues { it.value.get() }

    fun receivesByThread(): Map<String, Long> = receives.mapValues { it.value.get() }
}

/** Echoes every stream the 4.x server accepts, on [scope]; the body gets the server's port. */
internal suspend fun withEchoServer(
    unsolicited: suspend com.ditchoom.socket.quic.QuicScope.() -> Unit = {},
    body: suspend CoroutineScope.(port: Int) -> Unit,
) {
    withQuicServer(port = 0, tlsConfig = coreTlsConfig(), quicOptions = coreTestOptions) {
        coroutineScope {
            val server =
                launch(Dispatchers.IO) {
                    connections {
                        unsolicited()
                        while (true) {
                            val stream = acceptStream()
                            launch {
                                // The client closing the connection ends the echo; it is not a failure of the server.
                                try {
                                    while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                                } catch (closed: com.ditchoom.socket.quic.QuicCloseException) {
                                    Unit
                                }
                            }
                        }
                    }
                }
            body(port)
            server.cancel()
        }
    }
}

/**
 * The core suites' server certificate: ECDSA P-256 for localhost. The RSA-2048 one they used made each handshake an RSA
 * signature, the most of what a test of a thousand handshakes cost (4.0 s of it against 0.7 s on a JVM, 6.2 s against
 * 2.6 s on an emulator) and minutes of the API 29 lane's budget.
 */
internal fun coreTlsConfig(certificate: TestCertificate = TestCertificate.EcdsaP256): QuicTlsConfig =
    QuicTlsConfig(certChainPath = coreCertPath(certificate.chain), privKeyPath = coreCertPath(certificate.key))

/** A server certificate the test resources hold. */
internal enum class TestCertificate(
    val chain: String,
    val key: String,
) {
    EcdsaP256("pinned.crt", "pinned.key"),

    /** The one the simulation environment's modelled peer serves, so a recording replayed against it matches. */
    Rsa2048("cert.crt", "cert.key"),
}

/**
 * A path quiche can open. On a JVM the resource is a file on the classpath; on Android it lives inside
 * the test APK, so it is copied out once per name.
 */
private fun coreCertPath(name: String): String {
    val loader = CoreEdgeTests::class.java.classLoader
    val url = loader.getResource("certs/$name") ?: error("missing test resource certs/$name")
    if (url.protocol == "file") return java.io.File(url.toURI()).absolutePath
    val out =
        java.io.File
            .createTempFile("core-cert-", "-$name")
            .apply { deleteOnExit() }
    url.openStream().use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
    return out.absolutePath
}
