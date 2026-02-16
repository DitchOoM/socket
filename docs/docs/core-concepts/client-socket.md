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
val conn = SocketConnection.connect(
    hostname = "example.com",
    port = 443,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
        maxPoolSize = 64,
        readTimeout = 10.seconds,
    ),
)

// Use the buffer pool
conn.withBuffer { buffer ->
    buffer.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
    buffer.resetForRead()
    conn.write(buffer)
}

// Read into the stream processor
conn.readIntoStream()

conn.close()
```

## Allocation

For more control, allocate a socket manually and then open it:

```kotlin
val socket = ClientSocket.allocate()
socket.open(port = 80, timeout = 15.seconds, hostname = "example.com")
// ... use socket ...
socket.close()
```
