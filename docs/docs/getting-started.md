---
sidebar_position: 2
title: Getting Started
---

# Getting Started

## Installation

Add the socket dependency to your `build.gradle.kts`:

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

## Requirements

- **JDK 21** for building (configured via Gradle toolchain)
- **Kotlin 2.0+** (Kotlin Multiplatform)
- **macOS** required for building Apple targets

## Your First Connection

### Request/Response

Connect, send a request, read the response, close:

```kotlin
val socket = ClientSocket.connect(port = 80, hostname = "example.com")
socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
val response = socket.readString()
socket.close()
```

### Lambda-Scoped Connections

For automatic resource cleanup, use the lambda variant:

```kotlin
val response = ClientSocket.connect(80, hostname = "example.com") { socket ->
    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
    socket.readString()
} // socket closed automatically
```

## Streaming

For connections that stay open and emit data continuously, use `readFlowLines()`:

```kotlin
ClientSocket.connect(8883, hostname = "broker.example.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("SUBSCRIBE events\n")
    socket.readFlowLines().collect { line ->
        println(line)
    }
}
```

Or return a cold Flow — the socket opens when collection starts:

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

## Echo Server

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind()

launch {
    clients.collect { client ->
        val message = client.readString()
        client.writeString(message) // echo back
        client.close()
    }
}

val client = ClientSocket.connect(server.port())
client.writeString("Hello!")
val echo = client.readString() // "Hello!"
client.close()
server.close()
```

## Next Steps

- [Client Socket](./core-concepts/client-socket) — Detailed client API with streaming patterns
- [Server Socket](./core-concepts/server-socket) — Server bind and accept
- [TLS](./core-concepts/tls) — Encrypted connections
- [Socket Options](./core-concepts/socket-options) — TCP tuning
- [Recipe: Building a Protocol Client](./guides/building-a-protocol) — Full-stack example
