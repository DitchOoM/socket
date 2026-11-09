@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.allocate
import com.ditchoom.socket.connect
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.Http3Request
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.webtransport.Http3WebTransportConfig
import com.ditchoom.socket.webtransport.webTransportSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions
import com.ditchoom.socket.webtransport.connect as connectWebTransport

/**
 * Every public operation, called from the real Android main thread, must suspend rather than block it.
 *
 * Each scenario runs on the main looper (a dispatcher over `Handler(Looper.getMainLooper())`) under a
 * `StrictMode.ThreadPolicy` with `detectAll()`: disk reads and writes, network calls (DNS included),
 * unbuffered I/O and custom slow calls on the main thread are all violations. API 28+ collects them
 * through `penaltyListener` and fails the test with each violation's stack; below 28 there is no
 * listener, so the policy is `penaltyDeath()` and a violation kills the test process — loudly.
 *
 * The desktop JVM counterpart is `:socket-testsuite:mainThreadSafetyTest` (BlockHound). Unlike that
 * suite, the instrumented tests of a module share one process, so the one-time work of a first use (the
 * native library load) is covered here only when this class happens to run first; everything a
 * connection does on every use is covered regardless.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMainThreadSafetyTests {
    private val quicOptions = QuicOptions(alpnProtocols = listOf("main-thread"), verifyPeer = false, idleTimeout = 10.seconds)
    private val h3Options = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), verifyPeer = false, idleTimeout = 10.seconds)
    private val wtOptions = h3Options.copy(datagrams = DatagramOptions())
    private val transport = TransportConfig(bufferFactory = BufferFactory.deterministic())
    private lateinit var tls: QuicTlsConfig

    /**
     * The test's own I/O, before any scenario: extracting the bundled certificate files, and the buffer
     * library's one-time native load on its first native-memory allocation (the test allocates its
     * payloads on the main thread; that is the buffer library's call, not this library's).
     */
    @Before
    fun prepareOffTheMainThread() {
        tls = AndroidTestCerts.tlsConfig
        BufferFactory.deterministic().allocate(1).freeIfNeeded()
    }

    @Test
    fun tcpServerBindAcceptAndClientRoundTrip() =
        onMainThread {
            val server = ServerSocket.allocate()
            val accepted = server.bind(host = "localhost")
            val echo =
                launch {
                    val peer = accepted.first()
                    peer.writeString("echo:" + peer.readString())
                    peer.close()
                }
            ClientSocket.connect(server.port(), "localhost") { socket ->
                socket.writeString("hello")
                assertEquals("echo:hello", socket.readString())
            }
            echo.join()
            server.close()
        }

    @Test
    fun tlsClientRoundTripAndVerifyingConnect() {
        val server = LoopbackTlsEchoServer()
        try {
            onMainThread {
                ClientSocket.connect(server.port, "localhost", TransportConfig.tlsInsecure()) { socket ->
                    socket.writeString("hello")
                    assertEquals("echo:hello", socket.readString())
                }
                // The default config verifies, so it must reject the self-signed peer — after loading the
                // platform trust store, which is the part that must not happen on the main thread.
                assertFails {
                    ClientSocket.connect(server.port, "localhost", TransportConfig.tlsDefault()) { socket ->
                        socket.writeString("hello")
                        socket.readString()
                    }
                }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun udpBindConnectSendReceiveClose() =
        onMainThread {
            val server = UdpSocket.bind(localHost = "127.0.0.1")
            val client = UdpSocket.connect("localhost", server.localAddress.port)
            try {
                client.send(text("ping"))
                val request = assertIs<DatagramReadResult.Received>(server.receive())
                server.send(text("pong"), to = request.datagram.peer)
                request.datagram.payload.freeIfNeeded()
                val reply = assertIs<DatagramReadResult.Received>(client.receive())
                assertEquals("pong", reply.datagram.payload.let { it.readString(it.remaining(), Charset.UTF8) })
                reply.datagram.payload.freeIfNeeded()
            } finally {
                client.close()
                server.close()
            }
        }

    @Test
    fun resolveAHostname() =
        onMainThread {
            HostResolver.platform().resolve("localhost")
            UdpSocket.resolve("localhost", 9)
        }

    @Test
    fun quicServerAndClientStreamRoundTrip() =
        onMainThread {
            withQuicServer(port = 0, tlsConfig = tls, quicOptions = quicOptions) {
                val echo =
                    launch {
                        connections {
                            val stream = acceptStream()
                            val data = stream.read(5.seconds)
                            if (data is ReadResult.Data) {
                                try {
                                    stream.writeFully(text("echo:"), 5.seconds)
                                    stream.writeFully(data.buffer, 5.seconds)
                                } finally {
                                    data.buffer.freeIfNeeded()
                                }
                            }
                            stream.close()
                        }
                    }
                try {
                    withQuicConnection("localhost", port, quicOptions, timeout = 10.seconds) {
                        val stream = openStream()
                        stream.writeFully(text("hello"), 5.seconds)
                        val reply = StringBuilder()
                        while (reply.length < "echo:hello".length) {
                            val read = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                            reply.append(assertIs<ScopedRead.Data<String>>(read).value)
                        }
                        assertEquals("echo:hello", reply.toString())
                        stream.close()
                    }
                } finally {
                    echo.cancel()
                }
            }
        }

    @Test
    fun http3ServerAndRequest() =
        onMainThread {
            withHttp3Server(
                port = 0,
                tlsConfig = tls,
                quicOptions = h3Options,
                connectionOptions = transport,
                onRequest = { response.send(204) },
            ) {
                val status =
                    withHttp3Connection("localhost", port, h3Options, transport, 10.seconds) {
                        val response = request(Http3Request(method = "GET", authority = "localhost", path = "/"))
                        try {
                            response.readFullBody().freeIfNeeded()
                            response.status
                        } finally {
                            response.close()
                        }
                    }
                assertEquals(204, status)
            }
        }

    @Test
    fun webTransportSessionBidiRoundTrip() =
        onMainThread {
            withHttp3Server(
                port = 0,
                tlsConfig = tls,
                quicOptions = wtOptions,
                connectionOptions = transport,
                webTransport = Http3WebTransportOptions(maxSessions = 4),
                onWebTransport = {
                    val session = accept()
                    val stream = session.incomingBidiStreams.first()
                    val message = stream.readUtf8()
                    stream.write(text("echo:$message"))
                    stream.close()
                },
                onRequest = { response.send(404) },
            ) {
                val session =
                    webTransportSupport().connectWebTransport("https://localhost:$port/wt", Http3WebTransportConfig(wtOptions, transport))
                try {
                    val stream = session.openBidiStream()
                    stream.write(text("hello"))
                    (stream as HalfCloseable).shutdownSend()
                    assertEquals("echo:hello", stream.readUtf8())
                } finally {
                    session.close()
                }
            }
        }

    private fun text(s: String): PlatformBuffer =
        BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
            writeString(s, Charset.UTF8)
            resetForRead()
        }

    private suspend fun ByteSource.readUtf8(): String {
        val sb = StringBuilder()
        while (true) {
            when (val result = read()) {
                is ReadResult.Data -> {
                    sb.append(result.buffer.readString(result.buffer.remaining(), Charset.UTF8))
                    result.buffer.freeIfNeeded()
                }
                ReadResult.End, ReadResult.Reset -> return sb.toString()
            }
        }
    }

    /**
     * Runs [block] on the Android main thread under a StrictMode policy that detects everything, and
     * fails with every violation it reported. The caller (the instrumentation thread) only waits.
     */
    private fun onMainThread(
        timeout: Duration = 60.seconds,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        // kotlin.test finds its asserter through ServiceLoader (a disk read) on first use: the test's own
        // I/O, done here rather than on the main thread in the middle of a scenario.
        kotlin.test.asserter
        val mainHandler = Handler(Looper.getMainLooper())
        val main = Executor { mainHandler.post(it) }.asCoroutineDispatcher()
        val violations = ConcurrentLinkedQueue<Throwable>()
        runBlocking {
            withContext(main) {
                if (Looper.myLooper() != Looper.getMainLooper()) fail("not on the main thread")
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(strictPolicy(violations))
                try {
                    withTimeout(timeout) { block() }
                } finally {
                    StrictMode.setThreadPolicy(previous)
                }
            }
        }
        // The main thread hands its violations over when its current message ends; let the looper drain
        // and the listener's executor run before reading them.
        val drained = CountDownLatch(1)
        mainHandler.post { drained.countDown() }
        drained.await(5, TimeUnit.SECONDS)
        Thread.sleep(VIOLATION_DELIVERY_MS)
        if (violations.isNotEmpty()) {
            val report = violations.joinToString("\n\n") { it.stackTraceToString() }
            throw AssertionError("${violations.size} StrictMode violation(s) on the main thread:\n$report")
        }
    }

    private fun strictPolicy(violations: ConcurrentLinkedQueue<Throwable>): StrictMode.ThreadPolicy {
        val builder = StrictMode.ThreadPolicy.Builder().detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.penaltyListener(Executor { it.run() }) { violation -> violations += violation }
        } else {
            builder.penaltyLog().penaltyDeath()
        }
        return builder.build()
    }

    /**
     * A JDK TLS server on a background thread, presenting the bundled test certificate: test
     * scaffolding, not the API under test, and built before the main thread is involved.
     */
    private class LoopbackTlsEchoServer : AutoCloseable {
        private val socket = serverContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket
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
                                it.getInputStream().read()
                            }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()

        private companion object {
            fun serverContext(): SSLContext {
                val certificate =
                    java.io.File(AndroidTestCerts.path("cert.crt")).inputStream().use {
                        CertificateFactory.getInstance("X.509").generateCertificate(it)
                    }
                val pem = java.io.File(AndroidTestCerts.path("cert.key")).readText()
                val der =
                    android.util.Base64.decode(
                        pem.lines().filterNot { it.startsWith("-----") }.joinToString(""),
                        android.util.Base64.DEFAULT,
                    )
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
                val password = "loopback".toCharArray()
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                keyStore.setKeyEntry("server", key, password, arrayOf(certificate))
                val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagers.init(keyStore, password)
                // An empty trust-manager list, not null: null would load the platform trust store here
                // and warm the cache the client must load for itself.
                return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, arrayOf(), null) }
            }
        }
    }

    private companion object {
        const val VIOLATION_DELIVERY_MS = 300L
    }
}
