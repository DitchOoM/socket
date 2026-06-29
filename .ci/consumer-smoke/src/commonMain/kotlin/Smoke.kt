package consumer.smoke

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.Http3Request
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.quic.QuicOptions
import kotlin.time.Duration.Companion.seconds

/**
 * Touches the published API surface a real consumer uses, on EVERY declared target. Compiling this
 * in commonMain is already stronger than dependency resolution — it catches an API that resolves but
 * does not compile. [connectAndGet] additionally references [withHttp3Connection], which on Kotlin/Native
 * pulls the whole QUIC backend (socket-http3 → socket-quic → socket-quic-quiche cinterop → libquiche.a):
 * linking a K/N test binary that reaches it is what turns a missing/undistributed static lib into the
 * link-time undefined-symbol failure it really is, rather than letting it slip past resolution.
 */
object Smoke {
    fun apiSurface(): QuicOptions = QuicOptions(alpnProtocols = listOf("h3"), verifyPeer = false, idleTimeout = 10.seconds)

    /** Never asserted on here — its purpose is to make the full QUIC/H3 stack reachable for the K/N linker. */
    suspend fun connectAndGet(host: String, port: Int): Int =
        withHttp3Connection(
            host,
            port,
            apiSurface(),
            TransportConfig(bufferFactory = BufferFactory.deterministic()),
            timeout = 5.seconds,
        ) {
            val r = request(Http3Request(method = "GET", authority = host, path = "/"))
            try {
                r.status
            } finally {
                r.close()
            }
        }
}
