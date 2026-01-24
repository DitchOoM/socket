---
sidebar_position: 3
title: TLS/SSL
---

# TLS/SSL

Socket supports TLS/SSL encrypted connections on all platforms by passing `tls = true`.

## Client TLS

```kotlin
val socket = ClientSocket.connect(
    port = 443,
    hostname = "example.com",
    tls = true,
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
| Node.js | Node.js `tls` module |

## Lambda Variant

```kotlin
val response = ClientSocket.connect(443, hostname = "example.com", tls = true) { socket ->
    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
    socket.readString()
}
```

## Manual Allocation

```kotlin
val socket = ClientSocket.allocate(tls = true)
socket.open(port = 443, hostname = "example.com")
// ... use socket ...
socket.close()
```
