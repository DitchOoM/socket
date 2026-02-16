---
sidebar_position: 3
title: TLS/SSL
---

# TLS/SSL

Socket supports TLS/SSL encrypted connections on all platforms. TLS is enabled by passing a `SocketOptions` with a non-null `TlsConfig`.

## Client TLS

```kotlin
val socket = ClientSocket.connect(
    port = 443,
    hostname = "example.com",
    socketOptions = SocketOptions.tlsDefault(),
)
socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
val response = socket.readString()
socket.close()
```

## Platform Implementations

| Platform | TLS Implementation |
|----------|-------------------|
| JVM/Android | `SSLEngine` wrapping NIO sockets |
| Apple | Network.framework (native TLS in `NWConnection`) |
| Linux Native | OpenSSL 3.0 (statically linked for glibc compatibility) |
| Node.js | Node.js `tls` module |

## Lambda Variant

```kotlin
val response = ClientSocket.connect(443, hostname = "example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
    socket.readString()
}
```

## Streaming over TLS

TLS is a transparent layer — all streaming patterns work identically over encrypted connections:

```kotlin
ClientSocket.connect(8883, hostname = "broker.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("SUBSCRIBE events\n")
    socket.readFlowLines().collect { line ->
        println(line)
    }
}
```

Streaming with decompression over TLS:

```kotlin
ClientSocket.connect(443, hostname = "data.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.readFlow()
        .mapBuffer { decompress(it, Gzip).getOrThrow() }
        .asStringFlow()
        .lines()
        .collect { line -> process(line) }
}
```

## SocketConnection with TLS

For protocol implementations that need a buffer pool and stream processor with TLS:

```kotlin
SocketConnection.connect(
    hostname = "example.com",
    port = 443,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
    ),
) { conn ->
    // conn.pool, conn.stream, and conn.socket are all available
}
```

## Manual Allocation

```kotlin
val socket = ClientSocket.allocate()
socket.open(port = 443, hostname = "example.com", socketOptions = SocketOptions.tlsDefault())
// ... use socket ...
socket.close()
```
