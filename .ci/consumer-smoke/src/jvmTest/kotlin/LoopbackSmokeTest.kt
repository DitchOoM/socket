package consumer.smoke

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.Http3Request
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Behavioural smoke against the PUBLISHED artifacts: a real withHttp3Server and withHttp3Connection
 * over genuine QUIC on localhost (no external endpoint, no egress, deterministic). RUNNING this proves
 * the published JVM artifacts load their native lib (FFM on JDK21+ / JNI below), complete a QUIC
 * handshake, and round-trip an HTTP/3 request+response — none of which dependency resolution exercises.
 * This is the lane that would have caught the FFM-packaging and native-load classes of artifact bugs.
 */
class LoopbackSmokeTest {
    private val serverOpts = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), verifyPeer = false, idleTimeout = 10.seconds)
    private val clientOpts = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), verifyPeer = false, idleTimeout = 10.seconds)
    private val connOpts = TransportConfig(bufferFactory = BufferFactory.deterministic())
    private val tls = QuicTlsConfig(certChainPath = "testcerts/cert.crt", privKeyPath = "testcerts/cert.key")

    @Test
    fun http3RequestResponse_loopback() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHttp3Server(
                    port = 0,
                    tlsConfig = tls,
                    quicOptions = serverOpts,
                    connectionOptions = connOpts,
                    onRequest = { response.send(200) },
                ) {
                    delay(100)
                    val status =
                        withHttp3Connection("localhost", port, clientOpts, connOpts, 15.seconds) {
                            val r = request(Http3Request(method = "GET", authority = "localhost", path = "/"))
                            try {
                                r.readFullBody().freeIfNeeded()
                                r.status
                            } finally {
                                r.close()
                            }
                        }
                    assertEquals(200, status, "loopback HTTP/3 GET should return the server's 200")
                    println("[consumer-smoke] HTTP/3 loopback OK — status=$status from published artifacts")
                }
            }
        }
}
