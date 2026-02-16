---
sidebar_position: 2
title: Server Socket
---

# Server Socket

The `ServerSocket` interface allows you to listen for inbound TCP connections. Accepted connections are emitted as a `Flow<ClientToServerSocket>`.

## Basic Usage

```kotlin
val server = ServerSocket.allocate()
val clients: Flow<ClientToServerSocket> = server.bind()

launch {
    clients.collect { client ->
        // Handle each connected client
        val message = client.readString()
        client.writeString("Echo: $message")
        client.close()
    }
}
```

## Binding Options

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind(
    port = 8080,     // specific port, or use default for OS-assigned
    backlog = 128,   // max pending connections
)
```

## Server State

```kotlin
val isListening: Boolean = server.isListening()
val port: Int = server.port() // -1 if not bound
```

## Closing

```kotlin
server.close() // stops accepting new connections
```

## Echo Server

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind()

launch {
    clients.collect { client ->
        val data = client.readString()
        client.writeString(data)
        client.close()
    }
}

// Test with a client
val client = ClientSocket.connect(server.port())
client.writeString("Hello!")
assertEquals("Hello!", client.readString())
client.close()
server.close()
```

## Streaming Echo Server

An echo server that streams lines back to each client:

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind(port = 9000)

clients.collect { client ->
    launch {
        client.readFlowLines().collect { line ->
            client.writeString("Echo: $line\n")
        }
        client.close()
    }
}
```
