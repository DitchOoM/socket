package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** The options every core test connects with. */
internal val coreTestOptions = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

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

/** The prefix of the loop threads' names, as [coreConnect] starts them. */
internal const val LOOP_THREAD = "quic-core-client"

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

internal suspend fun connectCore(
    port: Int,
    host: String = "127.0.0.1",
    loops: LoopSet = LoopSet.shared,
    factory: BufferFactory = BufferFactory.Default,
    api: com.ditchoom.socket.quic.QuicheApi = loadQuicheApi(),
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
): CoreClient =
    coreConnect(
        host = host,
        port = port,
        serverName = "localhost",
        options = coreTestOptions,
        factory = factory,
        api = api,
        timeout = 10.seconds,
        readPolicy = ReadPolicy.Bounded(10.seconds),
        writePolicy = WritePolicy.Bounded(10.seconds),
        wrapIo = wrapIo,
        loops = loops,
        flight = TEST_FLIGHT,
    )

/**
 * The flight recording of a test's client connection: a server's size, so a thousand connections in one test JVM fit
 * its direct-memory limit (the default 256 KiB arena each would take 256 MiB on its own).
 */
internal val TEST_FLIGHT =
    com.ditchoom.socket.quic.core.conn
        .FlightSize(records = 256, payloadBytes = 32 * 1024)

/** Writes [payload] on [stream] while reading the echo; returns what came back. */
internal suspend fun echoOver(
    stream: QuicByteStream,
    payload: ByteArray,
): ByteArray {
    val received = ByteArrayOutputStream()
    coroutineScope {
        val reader =
            async {
                while (received.size() < payload.size) {
                    if (stream.read(10.seconds) { received.write(it.readByteArray(it.remaining())) } !is ScopedRead.Data<*>) break
                }
            }
        stream.writeFully(
            BufferFactory.Default.allocate(payload.size).apply {
                writeBytes(payload)
                resetForRead()
            },
            10.seconds,
        )
        reader.await()
    }
    return received.toByteArray()
}

internal fun coreTlsConfig(): QuicTlsConfig =
    QuicTlsConfig(certChainPath = coreCertPath("cert.crt"), privKeyPath = coreCertPath("cert.key"))

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

internal suspend fun <T> onIo(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)
