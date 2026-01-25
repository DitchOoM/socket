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
    hostname = "example.com", // defaults to localhost
    tls = false,              // set true for TLS
    timeout = 15.seconds,     // connection timeout
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

## Allocation

For more control, allocate a socket manually and then open it:

```kotlin
val socket = ClientSocket.allocate(tls = false)
socket.open(port = 80, timeout = 15.seconds, hostname = "example.com")
// ... use socket ...
socket.close()
```
