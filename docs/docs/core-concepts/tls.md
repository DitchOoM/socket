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

## SocketConnection with TLS

For protocol implementations that need a buffer pool and stream processor with TLS:

```kotlin
val conn = SocketConnection.connect(
    hostname = "example.com",
    port = 443,
    options = ConnectionOptions(
        socketOptions = SocketOptions.tlsDefault(),
    ),
)
// conn.pool, conn.stream, and conn.socket are all available
conn.close()
```

## Manual Allocation

```kotlin
val socket = ClientSocket.allocate()
socket.open(port = 443, hostname = "example.com", socketOptions = SocketOptions.tlsDefault())
// ... use socket ...
socket.close()
```
