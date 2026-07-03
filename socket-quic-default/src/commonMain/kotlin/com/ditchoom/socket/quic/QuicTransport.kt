package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.MultiplexingTransport
import com.ditchoom.socket.transport.SessionOwningByteStream
import com.ditchoom.socket.transport.SessionTransport
import com.ditchoom.socket.transport.Transport
import com.ditchoom.socket.transport.TransportFamily
import com.ditchoom.socket.transport.use
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
 * The QUIC front-door transport — implements **both** agnostic tiers (RFC_UNIFIED_ESTABLISHMENT.md
 * §3.1/§3.3/§3.4):
 *  - [Transport.connect] → the **single-stream projection**: establish a QUIC connection, open one
 *    bidirectional stream, hand it back as a plain [ByteStream] whose `close()` tears the connection
 *    down — so it behaves like TCP ([com.ditchoom.socket.transport.TcpTransport]) and any `Codec<T>`
 *    runs over it via `CodecConnection.connect(host, port, codec, QuicTransport(opts))`.
 *  - [MultiplexingTransport.withMux] → the **multiplex** tier: run a block with a typed [StreamMux]
 *    over the connection (many concurrent streams).
 *
 * Implementing both on one object is the fix for the "two-tier leak": a library holds a single
 * [Transport] and reaches multiplexing only where it exists, by `is`-check — the same type-gated
 * capability pattern as `WebTransportSupport.Multiplexed` — with no stubbed capability:
 *
 * ```kotlin
 * // app injects ONE object; library adapts by capability
 * fun MyProto(t: Transport) = if (t is MultiplexingTransport) t.withMux(...) { … } else oneStream(t)
 * ```
 *
 * TCP implements only [Transport], so the `is MultiplexingTransport` branch is correctly absent there.
 * For raw connection-level power (datagrams / migration / hand-managed lifetime), use
 * [QuicSessionTransport].
 *
 * Errors are unified onto the [com.ditchoom.socket.SocketException] family (RFC §6.1): a peer stream
 * reset ([QuicStreamException]) on the projected single stream is surfaced as
 * [SocketClosedException.ConnectionReset] (with the original as `cause`), since for a single-stream
 * projection a stream abort *is* connection loss; QUIC connection-close already throws
 * [QuicCloseException] (a [SocketClosedException]); establishment timeouts become [SocketTimeoutException].
 *
 * [quicOptions] carries the ALPN identifying the application protocol (required — QUIC has no default
 * ALPN); [engine] defaults to [defaultQuicEngine].
 */
class QuicTransport(
    quicOptions: QuicOptions,
    engine: QuicEngine = defaultQuicEngine,
) : Transport,
    MultiplexingTransport {
    private val session = QuicSessionTransport(quicOptions, engine)

    override val family: TransportFamily get() = TransportFamily.Udp

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

    override suspend fun <T, R> withMux(
        hostname: String,
        port: Int,
        codec: Codec<T>,
        config: TransportConfig,
        block: suspend StreamMux<T>.() -> R,
    ): R =
        // establish (timeout -> SocketTimeoutException) + close in finally; block runs with the mux.
        session.use(hostname, port, config) { connection ->
            QuicStreamMux(connection, codec, config).block()
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
