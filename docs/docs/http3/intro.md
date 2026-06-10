---
sidebar_position: 1
title: HTTP/3
---

# HTTP/3

`socket-http3` implements HTTP/3 (RFC 9114) — including QPACK header compression (RFC 9204), server
push (§4.6), and the WebTransport extension (RFC 9220) — on top of `socket-quic`. It's a separate
artifact:

```kotlin
dependencies {
    implementation("com.ditchoom:socket-http3:<latest-version>")
}
```

Like the rest of the stack, everything is scope-based: the connection (or server) lives for the
duration of your block and tears down on exit. HTTP/3 inherits QUIC's platform support — JVM/Android,
Linux native, and Apple; JS/wasmJs throw `UnsupportedOperationException`.

## Client: Request / Response

`withHttp3Connection` performs the QUIC handshake with the `h3` ALPN, bootstraps the HTTP/3 control
and QPACK streams, and hands you an `Http3Connection`:

```kotlin
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.freeIfNeeded

val (status, text) = withHttp3Connection("example.com", port = 443) {
    val response = request(
        Http3Request(method = "GET", authority = "example.com", path = "/"),
    )
    try {
        val body = response.readFullBody()        // collect the whole body
        val text = body.readString(body.remaining(), Charset.UTF8)
        body.freeIfNeeded()                        // you own the buffer
        response.status to text
    } finally {
        response.close()                           // release the response reader
    }
}
```

For large or streamed responses, pull DATA frames one at a time with `response.nextBodyChunk()`
(returns `null` at the end) instead of `readFullBody()`. Trailing headers, if any, land in
`response.trailers`.

### Streaming a request body

To send a body incrementally instead of buffering it, use the lambda form — it hands you an
`Http3RequestBody` you write DATA frames into:

```kotlin
val response = request(
    method = "POST",
    authority = "example.com",
    path = "/upload",
) {
    // `this` is an Http3RequestBody — each write() sends one DATA frame, in order.
    write(part1)
    write(part2)
}
```

## Server

`withHttp3Server` binds a QUIC server and routes each request to your `onRequest` handler, which
receives an `Http3ServerExchange` (its `request` and `response`):

```kotlin
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.use
import com.ditchoom.socket.quic.network

withHttp3Server(
    port = 0, // ephemeral; read it back from `port`
    tlsConfig = QuicTlsConfig(certChainPath = "cert.pem", privKeyPath = "key.pem"),
    onRequest = {
        when (request.path) {
            "/hello" -> {
                // `send` writes the body but doesn't take ownership — `use { }` frees it after.
                // QUIC buffers must be native memory; `BufferFactory.network()` guarantees that.
                BufferFactory.network().allocate(64).use { body ->
                    body.writeString("hello from h3", Charset.UTF8)
                    body.resetForRead()
                    response.send(
                        status = 200,
                        headers = listOf(QpackHeaderField("content-type", "text/plain")),
                        body = body,
                    )
                }
            }
            else -> response.send(404)
        }
    },
) {
    // `this` is the Http3Server; serve until this block is cancelled.
    awaitCancellation()
}
```

`response.send(...)` is the buffered convenience (headers + optional body + finish). For streamed
responses, call `sendHeaders(status, headers)`, then `writeBody(chunk)` per DATA frame, and finally
`sendTrailers(...)` if needed. Read a request body with `request.readFullBody()` or
`request.nextBodyChunk()`.

### Server push (RFC 9114 §4.6)

From inside a handler, `push(...)` promises and sends an extra response the client didn't ask for
(enable it client-side with `maxPushId >= 0` on `withHttp3Connection`, and collect `connection.pushes`):

```kotlin
onRequest = {
    response.send(200, body = indexHtml)
    push(path = "/style.css") {
        send(200, body = css) // `this` is the pushed Http3ServerResponse
    }
}
```

## Next Steps

- [WebTransport](./webtransport) — bidirectional sessions, streams, and datagrams over HTTP/3
- [QUIC Overview](../quic/intro) — the transport HTTP/3 is built on
