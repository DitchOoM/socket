---
slug: /
sidebar_position: 1
title: Introduction
---

# Socket

**Cross-platform TCP + TLS with streaming, compression, and buffer pooling — same Kotlin code on 6 platforms**

Socket provides suspend-based async socket I/O with platform-native implementations, allowing you to write networking code once and run it across JVM, Android, iOS, macOS, Linux, and Node.js.

## Why Socket?

- **One-line TLS** — `SocketOptions.tlsDefault()` handles SSLEngine, Network.framework, OpenSSL, and Node tls module
- **Streaming** — `readFlowLines()` with backpressure, cancellation, and constant memory
- **Buffer pooling** — `SocketConnection` bundles pool + stream processor, no per-message allocations
- **Compression** — compose `mapBuffer { decompress(it) }` into any read pipeline
- **Lambda-scoped connections** — auto-cleanup, no try-finally
- **Platform-native performance** — io_uring on Linux, NWConnection on Apple, NIO2 on JVM

## Platform Implementations

| Platform | Native Type | Notes |
|----------|-------------|-------|
| JVM | [`AsynchronousSocketChannel`](https://docs.oracle.com/en/java/javase/12/docs/api/java.base/java/nio/channels/AsynchronousSocketChannel.html) | NIO2 with NIO fallback |
| Android | Same as JVM | Shared `commonJvmMain` source set |
| iOS/macOS/tvOS/watchOS | [`NWConnection`](https://developer.apple.com/documentation/network/nwconnection) | Network.framework, zero-copy |
| Linux (x64/arm64) | [`io_uring`](https://kernel.dk/io_uring.pdf) | Kernel 5.1+, zero-copy, static OpenSSL |
| Node.js | [`net.Socket`](https://nodejs.org/api/net.html#class-netsocket) | Node.js networking API |

## Quick Example

### Request/Response

```kotlin
val response = ClientSocket.connect(443, hostname = "example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
    socket.readString()
} // socket closed automatically
```

### Persistent Streaming

```kotlin
ClientSocket.connect(8883, hostname = "broker.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("SUBSCRIBE events\n")
    socket.readFlowLines().collect { line ->
        println(line)
    }
}
```

### Streaming with Compression

```kotlin
socket.readFlow()
    .mapBuffer { decompress(it, Gzip).getOrThrow() }
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

## Part of the DitchOoM Stack

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

## Installation

Add to your `build.gradle.kts` (see [Maven Central](https://central.sonatype.com/search?q=com.ditchoom) for latest versions):

```kotlin
dependencies {
    implementation("com.ditchoom:socket:<latest-version>")
    // Optional: streaming transforms (mapBuffer, asStringFlow, lines)
    implementation("com.ditchoom:buffer-flow:<latest-version>")
    // Optional: compression
    implementation("com.ditchoom:buffer-compression:<latest-version>")
}
```

## Next Steps

- [Getting Started](./getting-started) — Installation and first connection
- [Client Socket](./core-concepts/client-socket) — Connect, read, write, stream, and close
- [Server Socket](./core-concepts/server-socket) — Accept inbound connections
- [TLS](./core-concepts/tls) — Encrypted connections
- [Recipe: Building a Protocol Client](./guides/building-a-protocol) — Full-stack example with streaming, TLS, buffer pools, and compression
