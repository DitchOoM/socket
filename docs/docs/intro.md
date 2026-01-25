---
slug: /
sidebar_position: 1
title: Introduction
---

# Socket

**Kotlin Multiplatform library for cross-platform TCP socket networking**

Socket provides suspend-based async socket I/O with platform-native implementations, allowing you to write networking code once and run it across JVM, Android, iOS, macOS, and Node.js.

## Why Socket?

- **Suspend-based API**: All I/O operations are coroutine-friendly suspend functions
- **Zero-copy transfers**: Direct delegation to platform-native socket APIs
- **TLS/SSL support**: Encrypted connections on all platforms with `tls = true`
- **Server sockets**: Accept inbound connections as a `Flow<ClientToServerSocket>`
- **Flow-based reading**: Stream data via `readFlow()` and `readFlowString()`

## Platform Implementations

| Platform | Native Type | Notes |
|----------|-------------|-------|
| JVM | [`AsynchronousSocketChannel`](https://docs.oracle.com/en/java/javase/12/docs/api/java.base/java/nio/channels/AsynchronousSocketChannel.html) | NIO2 with NIO fallback |
| Android | Same as JVM | Shared `commonJvmMain` source set |
| iOS/macOS/tvOS/watchOS | [`NWConnection`](https://developer.apple.com/documentation/network/nwconnection) | Network.framework, zero-copy |
| Node.js | [`net.Socket`](https://nodejs.org/api/net.html#class-netsocket) | Node.js networking API |

## Quick Example

```kotlin
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.connect

// Connect, write, read, close
val socket = ClientSocket.connect(
    port = 443,
    hostname = "example.com",
    tls = true
)
socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
val response = socket.readString()
socket.close()
```

## Installation

Add to your `build.gradle.kts` (see [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/socket) for the latest version):

```kotlin
dependencies {
    implementation("com.ditchoom:socket:<latest-version>")
}
```

## Next Steps

- [Getting Started](./getting-started) - Installation and first connection
- [Client Socket](./core-concepts/client-socket) - Connect, read, write, and close
- [Server Socket](./core-concepts/server-socket) - Accept inbound connections
- [TLS](./core-concepts/tls) - Encrypted connections
