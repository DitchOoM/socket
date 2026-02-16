---
sidebar_position: 1
title: "Recipe: Building a Protocol Client"
---

# Recipe: Building a Protocol Client

This guide walks through building a real protocol client using the full DitchOoM stack: **socket** for networking, **buffer** for memory management, and **buffer-compression** for payload compression. The same code runs on JVM, Android, iOS, macOS, Linux, and Node.js.

## The Stack

```
┌─────────────────────────────────┐
│  Your Protocol (MQTT, WS, ...) │  ← You write this once
├─────────────────────────────────┤
│  buffer-compression (optional)  │  ← Gzip/Deflate
├─────────────────────────────────┤
│  SocketConnection               │  ← Pool + Stream + Socket
│  ┌────────────┬────────────────┐│
│  │ BufferPool │ StreamProcessor││  ← Reusable buffers, framing
│  └────────────┴────────────────┘│
│  SocketOptions + TlsConfig      │  ← TCP tuning + TLS
├─────────────────────────────────┤
│  Platform-native I/O            │  ← Zero-copy, async
│  io_uring │ NWConnection │ NIO2 │
└─────────────────────────────────┘
```

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.ditchoom:socket:<version>")
    implementation("com.ditchoom:buffer:<version>")
    // Optional: only if your protocol uses compression
    implementation("com.ditchoom:buffer-compression:<version>")
}
```

## Layer 1: Simple Connection

The simplest use — connect, write, read, close. This alone works across 6 platforms:

```kotlin
val response = ClientSocket.connect(
    port = 443,
    hostname = "api.example.com",
    socketOptions = SocketOptions.tlsDefault(),
) { socket ->
    socket.writeString("GET /health HTTP/1.1\r\nHost: api.example.com\r\nConnection: close\r\n\r\n")
    socket.readString()
}
// Socket closed automatically. Same code on JVM, iOS, Linux, Node.js.
```

**What the library handles for you:**
- TLS handshake (SSLEngine on JVM, Network.framework on Apple, OpenSSL on Linux)
- SNI (Server Name Indication) for virtual hosting
- Certificate validation against system trust stores
- Coroutine-friendly suspend I/O (no callbacks, no thread pools)
- Resource cleanup on lambda return

## Layer 2: SocketConnection with Buffer Pool

For protocols that exchange many messages, `SocketConnection` bundles a socket with a reusable `BufferPool` and `StreamProcessor` — avoiding per-message allocations:

```kotlin
val conn = SocketConnection.connect(
    hostname = "broker.example.com",
    port = 8883,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
        maxPoolSize = 64,          // reuse up to 64 buffers
        readTimeout = 30.seconds,
        writeTimeout = 10.seconds,
    ),
)

// Borrow a buffer from the pool, write a message, return it
conn.withBuffer(minSize = 256) { buf ->
    buf.writeByte(0x10)          // MQTT CONNECT packet type
    buf.writeByte(0x00)          // remaining length (placeholder)
    buf.writeString("MQTT")     // protocol name
    buf.resetForRead()
    conn.write(buf)
}

// Read response into the stream processor for framing
val bytesRead = conn.readIntoStream()

conn.close()
```

**What the buffer pool gives you:**
- Buffers are allocated once and reused — no GC pressure on JVM, no malloc churn on native
- `withBuffer` automatically returns the buffer to the pool
- `StreamProcessor` accumulates partial reads for protocols with framed messages
- `maxPoolSize` caps memory usage

## Layer 3: Adding Compression

For protocols that support compressed payloads (HTTP Content-Encoding, WebSocket permessage-deflate, MQTT v5 payload compression), add `buffer-compression`:

```kotlin
import com.ditchoom.buffer.compression.*

// Compress a payload before sending
val payload = """{"sensor":"temp","value":23.5,"ts":1700000000}""".toReadBuffer()
val compressed = compress(payload, CompressionAlgorithm.Gzip).getOrThrow()

conn.withBuffer(minSize = compressed.remaining() + 4) { buf ->
    buf.writeInt(compressed.remaining()) // length-prefix the frame
    buf.write(compressed)
    buf.resetForRead()
    conn.write(buf)
}

// Decompress after reading
val received = socket.read()
received.resetForRead()
val decompressed = decompress(received, CompressionAlgorithm.Gzip).getOrThrow()
val json = decompressed.readString(decompressed.remaining())
```

## Putting It All Together: A Line Protocol Client

Here's a complete, runnable example — a client for a simple newline-delimited JSON protocol over TLS:

```kotlin
import com.ditchoom.socket.*
import com.ditchoom.buffer.*
import com.ditchoom.buffer.compression.*
import kotlin.time.Duration.Companion.seconds

/**
 * Connects to a JSON-lines service over TLS, sends a request,
 * and reads newline-delimited responses.
 *
 * This code compiles and runs identically on:
 * - JVM/Android (NIO2 + SSLEngine)
 * - iOS/macOS (NWConnection + Network.framework TLS)
 * - Linux x64/arm64 (io_uring + OpenSSL)
 * - Node.js (net.Socket + tls module)
 */
suspend fun queryService(host: String, request: String): List<String> {
    val conn = SocketConnection.connect(
        hostname = host,
        port = 443,
        options = ConnectionOptions(
            socketOptions = SocketOptions.tlsDefault(),
            readTimeout = 10.seconds,
        ),
    )

    return try {
        // Send a compressed request with a length prefix
        val payload = request.toReadBuffer()
        val compressed = compress(payload, CompressionAlgorithm.Gzip).getOrThrow()

        conn.withBuffer(minSize = compressed.remaining() + 4) { buf ->
            buf.writeInt(compressed.remaining())
            buf.write(compressed)
            buf.resetForRead()
            conn.write(buf)
        }

        // Read responses line by line
        val lines = mutableListOf<String>()
        val sb = StringBuilder()

        conn.socket.readFlowString().collect { chunk ->
            sb.append(chunk)
            while ('\n' in sb) {
                val idx = sb.indexOf('\n')
                lines.add(sb.substring(0, idx))
                sb.delete(0, idx + 1)
            }
        }

        lines
    } finally {
        conn.close()
    }
}
```

## Server Side: Accept + Echo

The same APIs work for servers. Here's a TLS echo server that handles concurrent clients:

```kotlin
import com.ditchoom.socket.*
import kotlinx.coroutines.launch

val server = ServerSocket.allocate()
val clients = server.bind(port = 9000)

clients.collect { client ->
    launch {
        val message = client.readString()
        client.writeString(message)
        client.close()
    }
}
```

## What You Get for Free

The key value of this stack is what you **don't** have to write:

| Concern | Without this library | With socket + buffer |
|---------|---------------------|---------------------|
| **Platform I/O** | Separate implementations for NIO, NWConnection, io_uring, net.Socket | One `ClientSocket.connect()` call |
| **TLS** | Configure SSLEngine, SecureTransport, OpenSSL, and Node tls module separately | `SocketOptions.tlsDefault()` |
| **Buffer management** | Platform-specific ByteBuffer / NSData / Uint8Array | `ReadBuffer` / `WriteBuffer` everywhere |
| **Memory** | Manual pool management or GC pressure | `BufferPool` with `withBuffer` |
| **Stream parsing** | Roll your own accumulator for partial reads | `StreamProcessor` built in |
| **Compression** | Platform-specific zlib bindings | `compress()` / `decompress()` |
| **Coroutines** | Wrap callbacks in `suspendCancellableCoroutine` | Native suspend functions |
| **Resource cleanup** | Try-finally everywhere | Lambda-scoped connections, `SuspendCloseable` |

## Platform-Native Performance

Each platform uses its fastest available I/O primitive — no abstraction penalty:

- **Linux**: `io_uring` for kernel-level async I/O with zero-copy buffer submission. OpenSSL 3.0 statically linked for glibc compatibility.
- **Apple**: `NWConnection` via Network.framework — the same API that Safari and system services use. Zero-copy NSData buffer integration.
- **JVM/Android**: NIO2 `AsynchronousSocketChannel` with NIO `SocketChannel` fallback. Direct `ByteBuffer` allocation for zero-copy transfers.
- **Node.js**: Native `net.Socket` and `tls` module with proper backpressure handling.
