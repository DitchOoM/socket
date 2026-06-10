---
sidebar_position: 2
title: WebTransport
---

# WebTransport

WebTransport (RFC 9220 + draft-ietf-webtrans-http3) runs bidirectional sessions over an HTTP/3
connection. A session is established with an Extended CONNECT and then multiplexes its own streams
and datagrams — think of it as a browser-friendly, multiplexed alternative to WebSocket built on
QUIC.

Enable it on both ends by passing `WebTransportOptions`. `maxSessions` is how many peer-initiated
sessions you'll accept:

```kotlin
data class WebTransportOptions(val maxSessions: Long = 1)
```

## Establishing a Session

The **client** calls `connectWebTransport` after confirming the peer advertised support (via
`peerSettings()`):

```kotlin
withHttp3Connection(
    "example.com", port,
    webTransport = WebTransportOptions(maxSessions = 4),
) {
    check(peerSettings().webTransportSupported)
    val session = connectWebTransport(authority = "example.com", path = "/wt")
    // …use the session…
    session.close()
}
```

The **server** supplies an `onWebTransport` handler; call `accept()` to establish the session (or
`reject(status)` to refuse):

```kotlin
withHttp3Server(
    port = 0,
    tlsConfig = QuicTlsConfig("cert.pem", "key.pem"),
    webTransport = WebTransportOptions(maxSessions = 4),
    onWebTransport = {
        // `this` is a WebTransportServerExchange — inspect path/headers, then decide.
        if (path == "/wt") {
            val session = accept()
            // …use the session…
        } else {
            reject(404)
        }
    },
    onRequest = { response.send(404) }, // non-WebTransport requests
) {
    awaitCancellation()
}
```

## Streams

A session opens streams just like raw QUIC, but scoped to the session. Bidirectional streams are
`WebTransportStream` (read + write); unidirectional ones split into `WebTransportSendStream` and
`WebTransportReceiveStream`. They use the same `read(): ReadResult` / `write(buffer)` surface as
`QuicByteStream`.

```kotlin
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.use

// Client: open a bidi stream, send, half-close, read the reply.
val stream = session.openBidiStream()

// Allocate from the session's bufferFactory (the connection's native-memory factory).
session.bufferFactory.allocate(5).use { out ->
    out.writeString("hello", Charset.UTF8)
    out.resetForRead()
    stream.write(out)
}
stream.shutdownSend()

val reply = when (val r = stream.read()) {
    is ReadResult.Data -> {
        val s = r.buffer.readString(r.buffer.remaining(), Charset.UTF8)
        r.buffer.freeIfNeeded()
        s
    }
    ReadResult.End, ReadResult.Reset -> ""
}
stream.close()
```

The peer receives opened streams through flows on its session:

```kotlin
// Server: echo the first peer-opened bidirectional stream back with a prefix.
val stream = session.incomingBidiStreams.first()
val msg = when (val r = stream.read()) {
    is ReadResult.Data -> r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
    ReadResult.End, ReadResult.Reset -> ""
}
session.bufferFactory.allocate(64).use { out ->
    out.writeString("echo:$msg", Charset.UTF8)
    out.resetForRead()
    stream.write(out)
}
stream.close()
```

`incomingUniStreams: Flow<WebTransportReceiveStream>` is the unidirectional counterpart. To abort a
stream, `reset(errorCode)` (bidi/send) or `cancel(errorCode)` (receive) — the WebTransport
application code is mapped into the HTTP/3 error-code space per draft §4.3.

## Datagrams

A session sends and receives unreliable datagrams (RFC 9297), provided QUIC datagrams are enabled on
the underlying connection:

```kotlin
// Send.
session.bufferFactory.allocate(4).use { dgram ->
    dgram.writeString("ping", Charset.UTF8)
    dgram.resetForRead()
    session.sendDatagram(dgram)
}

// Receive — you own each emitted buffer.
val incoming = session.datagrams.first()
incoming.freeIfNeeded()
```

## Draining and Closing

Two distinct lifecycle signals:

- **Drain** (`drain()`, draft §5) asks the peer to *stop opening new streams* while keeping the
  session open so in-flight work finishes. The peer observes it via `isDrainRequested` or the
  `drained: Flow<Unit>` (which emits once, or completes silently if the session closes first).
- **Close** (`close(code, reason)`) ends the session with an application code + reason
  (WT_CLOSE_SESSION capsule + FIN). It's idempotent, and `awaitClosed()` suspends until the session
  ends, returning the `WebTransportCloseInfo`.

```kotlin
// Graceful wind-down: tell the peer to stop opening streams, drain in-flight work, then close.
session.drain()
// …finish outstanding streams/datagrams…
session.close(code = 0, reason = "done")
```

On the peer:

```kotlin
session.drained.collect { /* stop opening new streams */ }
val info = session.awaitClosed() // info.code, info.reason
```

## Next Steps

- [HTTP/3](./intro) — the request/response and server-push layer beneath WebTransport
- [QUIC: Connections & Streams](../quic/connection) — the stream model WebTransport streams inherit
