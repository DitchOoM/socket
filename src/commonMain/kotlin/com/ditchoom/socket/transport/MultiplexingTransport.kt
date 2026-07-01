package com.ditchoom.socket.transport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig

/**
 * The transport-agnostic **multiplexing** surface — the Layer-2 companion to [Transport]
 * (RFC_UNIFIED_ESTABLISHMENT.md §3.4). Where [Transport] hands a protocol library one
 * [com.ditchoom.buffer.flow.ByteStream], a [MultiplexingTransport] establishes a connection and runs a
 * block with a [StreamMux] — the buffer-flow abstraction for opening/accepting many independent typed
 * streams (bidi + uni) over one connection.
 *
 * This is what a library that genuinely needs multiple concurrent streams (not just one byte pipe)
 * codes against, so it works over **QUIC and WebTransport interchangeably** without naming either:
 *
 * ```kotlin
 * // library — depends on buffer-flow + socket-core only; transport is opaque
 * suspend fun serve(transport: MultiplexingTransport, host: String, port: Int) =
 *     transport.withMux(host, port, MyCodec) {                 // this: StreamMux<MyMsg>
 *         val control = openBidirectional()                    // Connection<MyMsg>
 *         val push = openUnidirectional()                      // Sender<MyMsg>
 *         val incoming = acceptBidirectional()                 // peer-initiated
 *         // …
 *     }
 *
 * // application picks the transport
 * serve(QuicMultiplexingTransport(QuicOptions(alpnProtocols = listOf("myproto"))), "h", 443)
 * serve(WebTransportMultiplexingTransport(path = "/myproto"), "h", 443)
 * ```
 *
 * **Scoped by design.** [StreamMux] does not own the connection lifecycle — the transport scope does
 * (see its docs). So the primitive is the scoped [withMux]: the connection lives for the block and all
 * streams are force-closed when it returns, exactly like `withQuicMux { }`. A single-stream protocol
 * that does not need multiplexing should use [Transport] instead (and gets TCP too).
 *
 * TCP deliberately does **not** implement this: TCP has no multiplexing, and a stubbed mux would be a
 * fake capability. The honest split is [Transport] for one stream (TCP/QUIC/WT) and
 * [MultiplexingTransport] for many (QUIC/WT).
 */
interface MultiplexingTransport {
    /**
     * Establish a multiplexed connection to [hostname]:[port], run [block] with the resulting typed
     * [StreamMux], and tear the connection down when [block] returns (normally, exceptionally, or via
     * cancellation). [codec] frames every stream the mux opens/accepts. Throws a
     * [com.ditchoom.socket.SocketException] if establishment fails.
     */
    suspend fun <T, R> withMux(
        hostname: String,
        port: Int,
        codec: Codec<T>,
        config: TransportConfig = TransportConfig(),
        block: suspend StreamMux<T>.() -> R,
    ): R
}
