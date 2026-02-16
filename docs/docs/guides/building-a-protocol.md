---
sidebar_position: 1
title: "Recipe: Building a Protocol Client"
---

# Recipe: Building a Protocol Client

This guide walks through building a real protocol client using the full DitchOoM stack. Each layer adds a capability — start with the simplest approach and add complexity only when needed.

## The Stack

```
┌──────────────────────────────────┐
│  Your Protocol (MQTT, WS, ...)   │
├──────────────────────────────────┤
│  buffer-compression (optional)   │  compress()/decompress() on ReadBuffer
├──────────────────────────────────┤
│  buffer-flow                     │  mapBuffer(), asStringFlow(), lines()
├──────────────────────────────────┤
│  SocketConnection                │  socket + pool + stream processor
│  ├─ BufferPool     ← reuse buffers, no GC pressure
│  ├─ StreamProcessor ← accumulate partial reads for framing
│  └─ ClientSocket    ← platform-native I/O
├──────────────────────────────────┤
│  ReadBuffer / WriteBuffer        │  same types everywhere
└──────────────────────────────────┘
```

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.ditchoom:socket:<version>")
    implementation("com.ditchoom:buffer:<version>")
    // Optional: streaming transforms
    implementation("com.ditchoom:buffer-flow:<version>")
    // Optional: compression
    implementation("com.ditchoom:buffer-compression:<version>")
}
```

## Layer 1: Request/Response

The simplest use — connect, write, read, close. Lambda scope handles cleanup:

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

## Layer 2: Persistent Streaming

For connections that stay open — event streams, pub/sub, log tailing — use `readFlowLines()`:

```kotlin
ClientSocket.connect(8883, hostname = "broker.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("SUBSCRIBE events\n")
    socket.readFlowLines().collect { line ->
        println(line)
    }
}
```

`readFlowLines()` handles `\n` and `\r\n` line endings, splits lines correctly across TCP chunk boundaries, and emits one line at a time in constant memory.

## Layer 3: Return a Flow

Wrap the connection in a cold Flow. The socket opens when collection starts and closes when done:

```kotlin
fun streamEvents(host: String): Flow<String> = flow {
    ClientSocket.connect(8883, hostname = host, socketOptions = SocketOptions.tlsDefault()) { socket ->
        socket.writeString("SUBSCRIBE events\n")
        emitAll(socket.readFlowLines())
    }
}
```

Now you get the full power of Flow composition:

```kotlin
// Filter, limit, compose
streamEvents("broker.example.com")
    .filter { "critical" in it }
    .take(100) // auto-closes socket after 100 lines
    .collect { alert(it) }

// Launch in a scope
streamEvents("broker.example.com")
    .onEach { process(it) }
    .launchIn(scope)

// Combine multiple streams
merge(
    streamEvents("broker-1.example.com"),
    streamEvents("broker-2.example.com"),
).collect { event -> handle(event) }
```

**What the streaming API gives you:**

| Concern | How |
|---------|-----|
| **Backpressure** | Slow collector suspends `read()` — no unbounded buffering |
| **Cancellation** | `.take(N)`, scope cancel, or exception closes the socket |
| **Constant memory** | Lines emitted one at a time, never accumulated |
| **Composition** | `filter`, `map`, `take`, `zip`, `combine` — all Flow operators |
| **Lazy connection** | Cold Flow — socket only opens when collection starts |
| **Cleanup** | Lambda-scoped connect closes socket on any exit path |

## Layer 4: Bidirectional Streaming

Read and write simultaneously using coroutines:

```kotlin
ClientSocket.connect(port, hostname = host, socketOptions = SocketOptions.tlsDefault()) { socket ->
    coroutineScope {
        // Writer: send commands
        launch {
            for (command in commandChannel) {
                socket.writeString("$command\n")
            }
        }

        // Reader: process responses
        socket.readFlowLines().collect { line ->
            handleResponse(line)
        }
    }
}
```

## Layer 5: Full Stack — Buffer Pool + Stream Processor + Compression

For protocols that exchange many messages (MQTT, WebSocket, custom binary protocols), `SocketConnection` bundles a socket with a reusable `BufferPool` and `StreamProcessor`:

```kotlin
SocketConnection.connect(
    hostname = "telemetry.example.com",
    port = 8883,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
        maxPoolSize = 64,
    ),
) { conn ->
    // Write compressed frames with pooled buffers
    sensorReadings.collect { reading ->
        conn.withBuffer { buf ->
            val payload = reading.toJson().toReadBuffer()
            val compressed = compress(payload, CompressionAlgorithm.Gzip).getOrThrow()
            buf.writeInt(compressed.remaining())
            buf.write(compressed)
            buf.resetForRead()
            conn.write(buf)
        }
    }
}
```

Read with decompression using `buffer-flow`:

```kotlin
socket.readFlow()
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

**What the buffer pool gives you:**
- Buffers are allocated once and reused — no GC pressure on JVM, no malloc churn on native
- `withBuffer` automatically returns the buffer to the pool
- `StreamProcessor` accumulates partial reads for protocols with framed messages
- `maxPoolSize` caps memory usage

## Layer 6: Large Data with Constant Memory

Process millions of records with backpressure. A slow collector suspends `read()` — no unbounded buffering:

```kotlin
ClientSocket.connect(port, hostname = host) { socket ->
    socket.readFlowLines()
        .take(1_000_000)  // stop after N records, socket auto-closes
        .collect { line ->
            db.insert(parseLine(line))
        }
}
```

## Server Side: Accept + Echo

The same APIs work for servers:

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind(port = 9000)

clients.collect { client ->
    launch {
        client.readFlowLines().collect { line ->
            client.writeString("Echo: $line\n")
        }
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
| **Line splitting** | Manual StringBuilder accumulator | `readFlowLines()` |
| **Streaming transforms** | Manual resetForRead + map boilerplate | `mapBuffer()`, `asStringFlow()` |
| **Backpressure** | Manual flow control | Built into Flow |
| **Coroutines** | Wrap callbacks in `suspendCancellableCoroutine` | Native suspend functions |
| **Resource cleanup** | Try-finally everywhere | Lambda-scoped connections, `SuspendCloseable` |

## Platform-Native Performance

Each platform uses its fastest available I/O primitive — no abstraction penalty:

- **Linux**: `io_uring` for kernel-level async I/O with zero-copy buffer submission. OpenSSL 3.0 statically linked for glibc compatibility.
- **Apple**: `NWConnection` via Network.framework — the same API that Safari and system services use. Zero-copy NSData buffer integration.
- **JVM/Android**: NIO2 `AsynchronousSocketChannel` with NIO `SocketChannel` fallback. Direct `ByteBuffer` allocation for zero-copy transfers.
- **Node.js**: Native `net.Socket` and `tls` module with proper backpressure handling.
