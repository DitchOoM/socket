---
sidebar_position: 1
title: Client Socket
---

# Client Socket

The `ClientSocket` interface is the primary API for connecting to remote servers. It extends `Reader`, `Writer`, and `SuspendCloseable`.

## Connecting

Use the `ClientSocket.connect()` extension function:

```kotlin
val socket = ClientSocket.connect(
    port = 80,
    hostname = "example.com",        // defaults to localhost
    timeout = 15.seconds,            // connection timeout
    socketOptions = SocketOptions(), // TCP and TLS configuration
)
```

## Reading

```kotlin
// Read raw bytes as a ReadBuffer
val buffer = socket.read()
buffer.resetForRead()

// Read as a string (UTF-8 default)
val text = socket.readString()

// Stream reads as a Flow
socket.readFlow().collect { buffer ->
    buffer.resetForRead()
    // process buffer
}

// Stream string reads
socket.readFlowString().collect { value ->
    // process string
}

// Stream complete lines (handles \n and \r\n, splits across chunks)
socket.readFlowLines().collect { line ->
    // process line
}
```

## Streaming Patterns

### Persistent Streaming

Keep a connection open and process data as it arrives:

```kotlin
ClientSocket.connect(8883, hostname = "broker.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("SUBSCRIBE events\n")
    socket.readFlowLines().collect { line ->
        println(line)
    }
}
```

### Returning a Flow

Wrap a connection in a cold Flow — the socket opens when collection starts and closes when done:

```kotlin
fun streamEvents(host: String): Flow<String> = flow {
    ClientSocket.connect(8883, hostname = host, socketOptions = SocketOptions.tlsDefault()) { socket ->
        socket.writeString("SUBSCRIBE events\n")
        emitAll(socket.readFlowLines())
    }
}

// Compose with Flow operators
streamEvents("broker.example.com")
    .filter { "critical" in it }
    .take(100) // auto-closes socket after 100 lines
    .collect { alert(it) }
```

### Streaming with Compression

Compose `mapBuffer`, `asStringFlow`, and `lines` from `buffer-flow`:

```kotlin
socket.readFlow()
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

### Large Data with Constant Memory

Process millions of records without accumulating them in memory. Backpressure is built into Flow — a slow collector suspends `read()`:

```kotlin
ClientSocket.connect(port, hostname = host) { socket ->
    socket.readFlowLines()
        .take(1_000_000)  // stop after N records, socket auto-closes
        .collect { line ->
            db.insert(parseLine(line))
        }
}
```

## Writing

```kotlin
// Write a buffer
val bytesWritten = socket.write(buffer)

// Write a string (UTF-8 default)
val bytesWritten = socket.writeString("Hello, server!")
```

## Connection State

```kotlin
val isOpen: Boolean = socket.isOpen()
val localPort: Int = socket.localPort()
val remotePort: Int = socket.remotePort()
```

## Closing

```kotlin
socket.close()
```

Or use the lambda variant for automatic cleanup:

```kotlin
val result = ClientSocket.connect(port, hostname) { socket ->
    socket.writeString("request")
    socket.readString()
} // socket closed when lambda returns
```

## SocketConnection (Pool + Stream)

For protocol implementations that need a buffer pool and stream processor, use `SocketConnection`:

```kotlin
SocketConnection.connect(
    hostname = "example.com",
    port = 443,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
        maxPoolSize = 64,
        readTimeout = 10.seconds,
    ),
) { conn ->
    // Use the buffer pool
    conn.withBuffer { buffer ->
        buffer.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
        buffer.resetForRead()
        conn.write(buffer)
    }

    // Read into the stream processor
    conn.readIntoStream()
}
```

## Compression

For compression over socket connections, add the optional `buffer-compression` module:

```kotlin
dependencies {
    implementation("com.ditchoom:buffer-compression:<version>")
}
```

It supports Gzip and Deflate and works with any `ReadBuffer`:

```kotlin
import com.ditchoom.buffer.compression.*

// Compress before writing
val payload = "Hello, World!".toReadBuffer()
val compressed = compress(payload, CompressionAlgorithm.Gzip).getOrThrow()
socket.write(compressed)

// Decompress after reading
val received = socket.read()
received.resetForRead()
val decompressed = decompress(received, CompressionAlgorithm.Gzip).getOrThrow()
```

This works with any socket configuration (plaintext, TLS, pooled connections).

## Allocation

For more control, allocate a socket manually and then open it:

```kotlin
val socket = ClientSocket.allocate()
socket.open(port = 80, timeout = 15.seconds, hostname = "example.com")
// ... use socket ...
socket.close()
```
