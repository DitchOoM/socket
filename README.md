Socket
==========

See the [project website][docs] for documentation and APIs.

Cross-platform TCP + TLS with streaming, compression, and buffer pooling — same Kotlin code on JVM, Android, iOS, macOS, Linux, and Node.js.

## Why Socket?

| Concern | Without | With socket + buffer |
|---------|---------|---------------------|
| **Platform I/O** | Separate NIO, NWConnection, io_uring, net.Socket | `ClientSocket.connect()` |
| **TLS** | Configure SSLEngine, SecureTransport, OpenSSL, tls separately | `SocketOptions.tlsDefault()` |
| **Buffer management** | Platform-specific ByteBuffer / NSData / Uint8Array | `ReadBuffer` / `WriteBuffer` everywhere via `BufferFactory` |
| **Memory** | Manual pool or GC pressure | `BufferPool` with `withBuffer`, `BufferFactory.deterministic()` for I/O |
| **Stream parsing** | Roll your own accumulator | `StreamProcessor` |
| **Compression** | Platform-specific zlib | `compress()` / `decompress()` on ReadBuffer |
| **Line splitting** | Manual StringBuilder accumulator | `readFlowLines()` |
| **Streaming transforms** | Manual resetForRead + map boilerplate | `mapBuffer()`, `asStringFlow()` |
| **Backpressure** | Manual flow control | Built into Flow |
| **Coroutines** | Wrap callbacks in `suspendCancellableCoroutine` | Native suspend functions |
| **Cleanup** | try-finally everywhere | Lambda-scoped connections |

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/socket.svg)](https://central.sonatype.com/artifact/com.ditchoom/socket)

```kotlin
dependencies {
    implementation("com.ditchoom:socket:<latest-version>")
    // Buffer v4 — required (BufferFactory API, deterministic allocation, scatter-gather)
    implementation("com.ditchoom:buffer:4.0.0")
    // Optional: streaming transforms (mapBuffer, asStringFlow, lines)
    implementation("com.ditchoom:buffer-flow:<latest-version>")
    // Optional: compression
    implementation("com.ditchoom:buffer-compression:<latest-version>")
}
```

Find the latest versions on [Maven Central](https://central.sonatype.com/search?q=com.ditchoom).

### Sister module: `socket-quic`

QUIC client and server live in a separate artifact so the core `socket` module stays small for TCP-only consumers. Same scope-based API shape, same buffer discipline:

```kotlin
dependencies {
    implementation("com.ditchoom:socket-quic:<latest-version>")
}

// Client — handshake completes before the block runs; connection closes on block exit.
withQuicConnection("example.com", 443, QuicOptions(alpnProtocols = listOf("h3"))) {
    val stream = openStream()
    stream.write(buf, 5.seconds)
    val response = stream.read(5.seconds)
    stream.close()
}

// Server — UDP socket bound for the block's lifetime.
withQuicServer(port = 4433, tlsConfig = QuicTlsConfig(certChainPath, privKeyPath), quicOptions) {
    connections {
        val stream = acceptStream()
        // … echo, multiplex, etc. …
    }
}
```

Backed by quiche on JVM/Android (JNI on JDK ≤20, FFM on JDK 21+), quiche cinterop on Linux native, and Network.framework on Apple. JS/wasmJs throw `UnsupportedOperationException` (no raw UDP).

Each `QuicByteStream` has independent send/receive sides: `write()`/`read()` for bytes, `shutdownSend()` to half-close the send side (FIN) for request/response, and `reset(errorCode)` to abort both directions. `read()` returns a `ReadResult` — `Data` (a buffer), `End` (peer FIN), or `Reset` (peer abort). Unreliable datagrams (RFC 9221) are available via `sendDatagram()`/`receiveDatagram()` when `QuicOptions.datagrams` is set.

Allocate send buffers from the scope's `bufferFactory` (defaults to `BufferFactory.network()` — the native-memory factory QUIC needs on every backend) and pair them with `use { }`; the connection scope reclaims streams on exit, but buffers are yours to free.

#### Typed stream multiplexing

`withQuicMux(..., codec) { … }` layers a `Codec<T>` over the connection so each stream exchanges typed messages instead of raw buffers:

```kotlin
withQuicMux("localhost", port, quicOptions, StringCodec) {
    val conn = openBidirectional()
    conn.send("hello")
    val reply = conn.receive().first() // "echo: hello"
    conn.close()
}
```

#### HTTP/3 & WebTransport — `socket-http3`

`socket-http3` builds HTTP/3 (RFC 9114, with QPACK + server push) and WebTransport (RFC 9220) on top of `socket-quic`:

```kotlin
// HTTP/3 client request/response
withHttp3Connection("example.com", 443) {
    val response = request(Http3Request(method = "GET", authority = "example.com", path = "/"))
    val body = response.readFullBody()
    body.freeIfNeeded() // you own the body buffer
    response.close()
}

// WebTransport session with its own multiplexed streams + datagrams
withHttp3Connection("example.com", port, webTransport = WebTransportOptions(maxSessions = 4)) {
    val session = connectWebTransport(authority = "example.com", path = "/wt")
    val stream = session.openBidiStream()
    stream.write(buf); stream.shutdownSend()
    session.close()
}
```

See the [QUIC][quic-docs] and [HTTP/3 & WebTransport][http3-docs] guides for the full API, including server roles, datagrams, and session drain/close.

## Error Handling

All socket errors are thrown as subtypes of the `SocketException` sealed hierarchy — catch broad categories or specific failure modes:

```kotlin
try {
    ClientSocket.connect(443, "example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
        socket.writeString("hello")
        socket.readString()
    }
} catch (e: SocketClosedException) {
    // Connection lost (reset, broken pipe, EOF)
} catch (e: SocketConnectionException.Refused) {
    // Nothing listening on that port
} catch (e: SocketUnknownHostException) {
    println("Cannot resolve: ${e.hostname}")
} catch (e: SSLSocketException) {
    // TLS certificate or protocol failure
}
```

## Reconnection

`ReconnectionClassifier` decides whether to retry or give up, with exponential backoff:

```kotlin
val classifier = DefaultReconnectionClassifier()
while (scope.isActive) {
    try {
        ClientSocket.connect(port, hostname, socketOptions) { socket ->
            classifier.reset() // connected — reset backoff
            socket.readFlowLines().collect { process(it) }
        }
    } catch (e: SocketException) {
        when (val decision = classifier.classify(e)) {
            is ReconnectDecision.RetryAfter -> delay(decision.delay)
            is ReconnectDecision.GiveUp -> break // TLS failure, DNS failure — won't recover
        }
    }
}
```

Protocol libraries (WebSocket, MQTT) layer domain-specific classifiers on top — see the [docs][docs].

## Quick Example

```kotlin
// Connect, write, read, close — same code on JVM, iOS, Linux, Node.js
val socket = ClientSocket.connect(
    port = 443,
    hostname = "example.com",
    socketOptions = SocketOptions.tlsDefault(),
)
socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
val response = socket.readString()
socket.close()
```

### Injecting a Buffer Allocation Strategy

`ClientSocket` exposes a `bufferFactory` property so you can control how internal read buffers are allocated. By default, I/O paths (TLS, NIO, io_uring) use `BufferFactory.deterministic()` for native memory with explicit cleanup. You can override this before the first read:

```kotlin
val socket = ClientSocket.connect(port = 9000, hostname = "localhost")
// Use managed (heap) buffers instead of native memory
socket.bufferFactory = BufferFactory.managed()
```

For `SocketConnection`, pass the factory via `ConnectionOptions`:

```kotlin
SocketConnection.connect(
    hostname = "broker.example.com",
    port = 8883,
    options = ConnectionOptions(
        bufferFactory = BufferFactory.deterministic(), // default — native memory, explicit cleanup
    ),
) { conn ->
    // ...
}
```

## Real-Time Streaming over TLS

```kotlin
ClientSocket.connect(8883, hostname = "broker.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("SUBSCRIBE events\n")
    socket.readFlowLines().collect { line ->
        println(line)
    }
} // socket closed automatically
```

## Streaming as a Flow

Return a cold Flow — the socket opens on collection and closes when done:

```kotlin
fun streamEvents(host: String): Flow<String> = flow {
    ClientSocket.connect(8883, hostname = host, socketOptions = SocketOptions.tlsDefault()) { socket ->
        socket.writeString("SUBSCRIBE events\n")
        emitAll(socket.readFlowLines())
    }
}

// Compose with standard Flow operators
streamEvents("broker.example.com")
    .filter { "critical" in it }
    .onEach { alert(it) }
    .launchIn(scope)
```

## Streaming with Compression

```kotlin
socket.readFlow()
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

## Scatter-Gather Writes

`writeGathered()` writes multiple buffers in a single call. Platforms may use true scatter-gather I/O under the hood (e.g., `GatheringByteChannel` on JVM NIO, `writev` on Linux):

```kotlin
val header = BufferFactory.Default.allocate(4)
header.writeInt(payload.remaining())
header.resetForRead()

socket.writeGathered(listOf(header, payload))
```

## Buffer Pooling with SocketConnection

For protocol implementations that exchange many messages, `SocketConnection` bundles a socket with a reusable `BufferPool` — no per-message allocations:

```kotlin
SocketConnection.connect(
    hostname = "broker.example.com",
    port = 8883,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
        maxPoolSize = 64,
    ),
) { conn ->
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

## Server Example

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind()

launch {
    clients.collect { client ->
        val message = client.readString()
        client.writeString(message) // echo
        client.close()
    }
}

// Connect a client
val client = ClientSocket.connect(server.port())
client.writeString("Hello!")
val echo = client.readString() // "Hello!"
client.close()
server.close()
```

## Part of the DitchOoM Stack

Socket builds on the [buffer v4](https://github.com/DitchOoM/buffer) library for zero-copy memory management. Buffer v4 introduces the `BufferFactory` API (replacing the old `AllocationZone` enum), `BufferFactory.deterministic()` for native memory with explicit cleanup, and `ScopedBuffer` for FFI/JNI-friendly allocation:

```
┌──────────────────────────────────┐
│  Your Protocol (MQTT, WS, ...)   │
├──────────────────────────────────┤
│  socket (TCP + TLS)              │  ← com.ditchoom:socket
├──────────────────────────────────┤
│  buffer-compression (optional)   │  ← com.ditchoom:buffer-compression
├──────────────────────────────────┤
│  buffer-flow                     │  ← com.ditchoom:buffer-flow
├──────────────────────────────────┤
│  buffer v4                       │  ← com.ditchoom:buffer:4.0.0
└──────────────────────────────────┘
```

**Buffer allocation strategies used by socket internally:**

| Path | Factory | Why |
|------|---------|-----|
| TLS handshake/unwrap (JVM) | `BufferFactory.deterministic()` | SSLEngine needs direct ByteBuffers with explicit cleanup |
| NIO read buffers (JVM) | `BufferFactory.deterministic()` | Channel reads require native memory |
| io_uring I/O (Linux) | `BufferFactory.deterministic()` | Kernel requires stable native addresses |
| General-purpose | `BufferFactory.Default` | Platform-optimal allocation, GC-managed |

## Platform Support

| Platform | Implementation |
|----------|---------------|
| JVM 1.8+ | `AsynchronousSocketChannel` / `SocketChannel` |
| Android | Same as JVM (shared source set) |
| iOS/macOS/tvOS/watchOS | `NWConnection` (Network.framework) |
| Linux (x64/arm64) | `io_uring` (kernel 5.1+, static OpenSSL) |
| Node.js | `net.Socket` |
| Browser | Not supported (throws `UnsupportedOperationException`) |

## License

    Copyright 2022 DitchOoM

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

[docs]: https://ditchoom.github.io/socket/
[quic-docs]: https://ditchoom.github.io/socket/quic/intro
[http3-docs]: https://ditchoom.github.io/socket/http3/intro
