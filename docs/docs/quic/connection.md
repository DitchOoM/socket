---
sidebar_position: 2
title: Connections & Streams
---

# Connections & Streams

A QUIC connection is a multiplexing fabric: you open as many streams as you need over it, and each
stream is an independent, ordered, reliable byte channel (`QuicByteStream`). The connection also
carries unreliable datagrams.

## Client

`withQuicConnection` opens a connection, runs your block with a `QuicScope` receiver, and closes the
connection on exit:

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.use
import kotlin.time.Duration.Companion.seconds

val reply = withQuicConnection(
    hostname = "example.com",
    port = 443,
    quicOptions = QuicOptions(alpnProtocols = listOf("h3")),
    timeout = 15.seconds,
) {
    val stream = openStream()

    // Write — buffers are written zero-copy; you retain ownership, so free it when done.
    // `use { }` frees the buffer on the way out, even if write() throws.
    BufferFactory.Default.allocate(11).use { out ->
        out.writeString("hello quic!", Charset.UTF8)
        out.resetForRead()
        stream.write(out, 5.seconds)
    }

    // We won't send anything more — half-close the write side (FIN); the read side stays open.
    stream.shutdownSend()

    // Read the response. The buffer in `Data` is yours to free.
    val text = when (val r = stream.read(5.seconds)) {
        is ReadResult.Data -> {
            val s = r.buffer.readString(r.buffer.remaining(), Charset.UTF8)
            r.buffer.freeIfNeeded()
            s
        }
        ReadResult.End, ReadResult.Reset -> ""
    }
    stream.close() // optional here — the scope would reclaim it — but sends a prompt FIN.
    text
}
```

## Server

`withQuicServer` binds a UDP socket and dispatches each accepted connection to a handler running on
its own `QuicScope`. Pass `port = 0` for an OS-assigned ephemeral port (read it back from `port`).

```kotlin
withQuicServer(
    port = 4433,
    tlsConfig = QuicTlsConfig(certChainPath = "cert.pem", privKeyPath = "key.pem"),
    quicOptions = QuicOptions(alpnProtocols = listOf("h3")),
) {
    connections {
        // `this` is the per-connection QuicScope. Accept a peer-opened stream and echo it.
        // A server connection is long-lived and may handle many streams, so release each one as
        // you finish it — `try/finally` guarantees the close even if the body throws.
        val stream = acceptStream()
        try {
            when (val r = stream.read(5.seconds)) {
                is ReadResult.Data -> {
                    stream.write(r.buffer, 5.seconds) // echo (zero-copy)
                    r.buffer.freeIfNeeded()           // you own the read buffer
                }
                ReadResult.End, ReadResult.Reset -> {}
            }
        } finally {
            stream.close()
        }
    }
}
```

`connections { … }` handles multiple connections concurrently — each invocation is a fresh scope.
You can also collect `streams(): Flow<QuicByteStream>` to react to every peer-opened stream.

:::tip Streams vs. buffers
Closing a stream is about *promptness* — the connection scope would reclaim it anyway on exit (see
[the overview](./intro#who-closes-what)). The thing you must not drop is a **buffer**: it isn't owned
by the scope. Allocate it inside `use { }`, and free the `ReadBuffer` you get back from `read()`.
:::

## The `QuicByteStream` Lifecycle

A stream has independent send and receive sides. The methods map directly onto QUIC frames:

| Call | Effect |
|------|--------|
| `write(buffer, timeout)` | Send bytes (zero-copy; you retain ownership of `buffer`). |
| `read(timeout): ReadResult` | Receive the next chunk. `Data` carries a buffer; `End` is the peer's FIN; `Reset` is a peer abort. |
| `shutdownSend()` | Half-close the **send** side only (FIN). The read side stays open — this is the request/response pattern. Idempotent. |
| `reset(errorCode)` | Abort **both** directions (`RESET_STREAM` + `STOP_SENDING`) with an application error code. Idempotent. |
| `close()` | Graceful close (FIN). Idempotent. |

`writeGathered(buffers, timeout)` does a scatter-gather write of several buffers as one operation.

Each stream carries a `streamId: QuicStreamId` whose bits tell you `isClientInitiated` /
`isServerInitiated` and `isBidirectional` / `isUnidirectional` (RFC 9000 §2.1).

### Unidirectional streams

`openUniStream()` opens a send-only stream. By convention you write a stream-type prefix as the
first bytes, then `close()` sends the FIN. (Not all platforms support locally-opened uni streams;
unsupported ones throw `UnsupportedOperationException`.)

## Datagrams (RFC 9221)

Unreliable datagrams are off by default — enable them by setting `QuicOptions.datagrams`. Each whole
buffer is one datagram; there is no retransmission, and oversized buffers are rejected.

```kotlin
val opts = QuicOptions(alpnProtocols = listOf("h3"), datagrams = DatagramOptions())

withQuicConnection("example.com", 443, opts) {
    // Send.
    BufferFactory.Default.allocate(4).use { dgram ->
        dgram.writeString("ping", Charset.UTF8)
        dgram.resetForRead()
        sendDatagram(dgram)
    }

    // Receive — the buffer's ownership transfers to you.
    when (val r = receiveDatagram()) {
        is DatagramReceiveResult.Received -> r.buffer.freeIfNeeded()
        is DatagramReceiveResult.ConnectionClosed -> { /* r.error: QuicError */ }
    }
}
```

`maxDatagramSize()` reports the current sendable size (`MaxDatagramSize.Bytes(n)` or `Unavailable`),
and `datagrams(): Flow<ReadBuffer>` is a flow form of the receive loop.

## Error Handling

QUIC errors mirror the core `socket` sealed hierarchy:

- **`QuicCloseException`** (a `SocketClosedException`) — the whole **connection** ended: peer close,
  idle timeout, handshake failure. Carries a structured `quicError: QuicError`.
- **`QuicStreamException`** — a single **stream** was aborted by the peer (`STOP_SENDING` or
  `RESET_STREAM`); the connection stays healthy. Inspect `abort: QuicStreamAbort` for the peer's
  application error code.

```kotlin
try {
    stream.write(buf, 5.seconds)
} catch (e: QuicStreamException) {
    // Just this stream died — e.abort.applicationErrorCode tells you why.
} catch (e: QuicCloseException) {
    // The connection is gone — e.quicError is the reason.
}
```

## Next Steps

- [Typed Stream Multiplexing](./stream-mux) — codec-typed messages instead of raw buffers
- [HTTP/3 & WebTransport](../http3/intro) — protocols built on this stream model
