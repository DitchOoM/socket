---
sidebar_position: 3
title: Typed Stream Multiplexing
---

# Typed Stream Multiplexing

Working with raw `QuicByteStream` buffers is fine for byte protocols, but most applications speak in
*messages*. `StreamMux<T>` layers a [`Codec<T>`](https://central.sonatype.com/search?q=com.ditchoom.buffer-codec)
over a QUIC connection so each stream sends and receives typed values — framing and buffer management
happen for you.

## The Entry Point

`withQuicMux` is `withQuicConnection` plus a codec. The block receives a `StreamMux<T>`:

```kotlin
suspend fun <T, R> withQuicMux(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    codec: Codec<T>,
    connectionOptions: ConnectionOptions = ConnectionOptions(),
    timeout: Duration = 15.seconds,
    block: suspend StreamMux<T>.() -> R,
): R
```

`StreamMux<T>` opens and accepts typed streams:

| Method | Returns | Use |
|--------|---------|-----|
| `openBidirectional()` | `Connection<T>` | Open a send+receive typed stream. |
| `openUnidirectional()` | `Sender<T>` | Open a send-only typed stream. |
| `acceptBidirectional()` | `Connection<T>` | Accept a peer-opened send+receive stream. |
| `acceptUnidirectional()` | `Receiver<T>` | Accept a peer-opened send-only stream. |

A `Connection<T>` is `send(value)`, `receive(): Flow<T>`, `close()`, and an `id` (the QUIC stream id).

## Defining a Codec

A `Codec<T>` encodes a value to a buffer, decodes one back, and — because streams are byte streams,
not message streams — tells the framing layer how big the next frame is so it can be split out of the
incoming bytes. Here is a length-prefixed UTF-8 string codec:

```kotlin
object StringCodec : Codec<String> {
    override fun encode(buffer: WriteBuffer, value: String, context: EncodeContext) {
        val bytes = value.encodeToByteArray()
        buffer.writeShort(bytes.size.toShort()) // 2-byte length prefix
        buffer.writeBytes(bytes)
    }

    override fun decode(buffer: ReadBuffer, context: DecodeContext): String {
        val length = buffer.readShort().toInt() and 0xFFFF
        return buffer.readString(length)
    }

    override fun wireSize(value: String, context: EncodeContext): WireSize = WireSize.BackPatch

    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
        // Need at least the 2-byte length to know the frame size.
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val length = stream.peekShort(baseOffset).toInt() and 0xFFFF
        return PeekResult.Complete(2 + length)
    }
}
```

`peekFrameSize` returns `PeekResult.Complete(n)` once a full frame is buffered, or
`PeekResult.NeedsMoreData` to wait for more bytes. `wireSize` lets the encoder pre-size its buffer
(`WireSize.Exact(n)` for fixed sizes, `WireSize.BackPatch` for variable ones).

## Client and Server

The client uses `withQuicMux`. The server gets a raw `QuicScope` from `withQuicServer`, so it wraps
it in a `QuicStreamMux` directly:

```kotlin
// --- Server: accept a typed bidi stream and echo with a prefix ---
withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = quicOptions) {
    connections {
        val mux = QuicStreamMux(this, StringCodec, ConnectionOptions())
        val conn = mux.acceptBidirectional()
        val msg = conn.receive().first()
        conn.send("echo: $msg")
        conn.close()
    }
}

// --- Client: open a typed bidi stream, send, receive ---
val response = withQuicMux("localhost", port, quicOptions, StringCodec) {
    val conn = openBidirectional()
    conn.send("hello")
    val reply = conn.receive().first()
    conn.close()
    reply
}
// response == "echo: hello"
```

`receive()` is a cold `Flow<T>` — collect it for a stream of messages, or take `.first()` for a
single request/response exchange as above.

## Next Steps

- [Connections & Streams](./connection) — the raw `QuicByteStream` model underneath the mux
- [HTTP/3 & WebTransport](../http3/intro) — higher-level protocols on QUIC
