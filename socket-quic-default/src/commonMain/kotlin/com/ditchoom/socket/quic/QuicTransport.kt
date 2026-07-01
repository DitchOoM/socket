package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.SessionOwningByteStream
import com.ditchoom.socket.transport.SessionTransport
import com.ditchoom.socket.transport.Transport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The Layer-2 multiplexed QUIC transport (RFC_UNIFIED_ESTABLISHMENT.md §3.2): establishes a held
 * [QuicConnection] over which the caller opens many streams and datagrams.
 *
 * This is the held ↔ scoped duality's one home for QUIC. `withQuicConnection { }` is the scoped form;
 * this is the held form, and [use][com.ditchoom.socket.transport.use] gives the scoped ergonomic over
 * it:
 *
 * ```kotlin
 * QuicSessionTransport(QuicOptions(alpnProtocols = listOf("myproto"))).use("example.com", 443) { scope ->
 *     val a = scope.openStream()
 *     val b = scope.openStream()
 * }   // both streams force-closed and the connection torn down here
 * ```
 *
 * [engine] defaults to the platform [defaultQuicEngine]; pass an explicit engine to override the
 * backend (the Ktor `HttpClient(engine)` model).
 */
class QuicSessionTransport(
    private val quicOptions: QuicOptions,
    private val engine: QuicEngine = defaultQuicEngine,
) : SessionTransport<QuicConnection> {
    override suspend fun establish(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): QuicConnection =
        try {
            withTimeout(config.connectTimeout) {
                engine.connect(hostname, port, quicOptions, config, config.connectTimeout)
            }
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException(
                "QUIC connect to $hostname:$port timed out after ${config.connectTimeout}",
                host = hostname,
                port = port,
                cause = e,
            )
        }

    override suspend fun close(session: QuicConnection) = session.close()
}

/**
 * The transport-agnostic **single-stream projection** of QUIC (RFC_UNIFIED_ESTABLISHMENT.md §3.1/§3.3):
 * a [Transport] whose [connect] establishes a QUIC connection, opens one bidirectional stream, and
 * hands it back as a plain [ByteStream]. Closing that stream tears the whole connection down — so it
 * behaves exactly like a TCP [com.ditchoom.socket.transport.TcpTransport] from a protocol library's
 * point of view, letting `CodecConnection.connect(host, port, codec, QuicTransport(opts))` run any
 * `Codec<T>` over QUIC with no protocol-code change.
 *
 * For multiplexing (many streams / datagrams over one connection), use [QuicSessionTransport] instead.
 *
 * Errors are unified onto the [com.ditchoom.socket.SocketException] family (RFC §6.1): a peer stream
 * reset ([QuicStreamException]) on the projected stream is surfaced as
 * [SocketClosedException.ConnectionReset] (with the original as `cause`), since for a single-stream
 * projection a stream abort *is* connection loss; QUIC connection-close already throws
 * [QuicCloseException], which is a [SocketClosedException]. Establishment timeouts become
 * [SocketTimeoutException].
 *
 * [quicOptions] carries the ALPN identifying the application protocol (required — QUIC has no default
 * ALPN); [engine] defaults to [defaultQuicEngine].
 */
class QuicTransport(
    quicOptions: QuicOptions,
    engine: QuicEngine = defaultQuicEngine,
) : Transport {
    private val session = QuicSessionTransport(quicOptions, engine)

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val connection = session.establish(hostname, port, config)
        val stream =
            try {
                connection.openStream()
            } catch (t: Throwable) {
                // Opening the first stream failed — don't leak the connection we just established.
                connection.close()
                throw t
            }
        return SessionOwningByteStream(
            stream = stream,
            closeSession = { connection.close() },
            mapError = ::mapStreamError,
        )
    }

    private companion object {
        /** Map a QUIC stream-level abort to the unified connection-lost error for the single-stream surface. */
        fun mapStreamError(t: Throwable): Throwable =
            when (t) {
                is QuicStreamException ->
                    SocketClosedException.ConnectionReset(
                        "QUIC stream ${t.streamId} reset by peer (${t.abort})",
                        cause = t,
                    )
                else -> t
            }
    }
}
