Socket
==========

See the [project website][docs] for documentation and APIs.

Cross-platform TCP + TLS with streaming, compression, and buffer pooling — same Kotlin code on JVM, Android, iOS, macOS, Linux, and Node.js.

## Why Socket?

| Concern | Without | With socket + buffer |
|---------|---------|---------------------|
| **Platform I/O** | Separate NIO, NWConnection, io_uring, net.Socket | `ClientSocket.connect()` |
| **TLS** | Configure SSLEngine, SecureTransport, OpenSSL, tls separately | `SocketOptions.tlsDefault()` |
| **Buffer management** | Platform-specific ByteBuffer / NSData / Uint8Array | `ReadBuffer` / `WriteBuffer` everywhere |
| **Memory** | Manual pool or GC pressure | `BufferPool` with `withBuffer` |
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
    // Optional: streaming transforms (mapBuffer, asStringFlow, lines)
    implementation("com.ditchoom:buffer-flow:<latest-version>")
    // Optional: compression
    implementation("com.ditchoom:buffer-compression:<latest-version>")
}
```

Find the latest versions on [Maven Central](https://central.sonatype.com/search?q=com.ditchoom).

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

Socket builds on the [buffer](https://github.com/DitchOoM/buffer) library for zero-copy memory management:

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
│  buffer                          │  ← com.ditchoom:buffer
└──────────────────────────────────┘
```

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
