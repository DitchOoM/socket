Socket
==========

See the [project website][docs] for documentation and APIs.

Socket is a Kotlin Multiplatform library for cross-platform TCP networking.
It provides suspend-based async socket I/O with platform-native implementations:

- **JVM/Android**: `AsynchronousSocketChannel` (NIO2) with `SocketChannel` (NIO) fallback
- **iOS/macOS/tvOS/watchOS**: `NWConnection` via Network.framework (zero-copy)
- **Node.js**: `net.Socket` API

## Features

- **Suspend-based API**: All I/O operations are coroutine-friendly suspend functions
- **Zero-copy transfers**: Direct delegation to platform-native socket APIs
- **TLS/SSL support**: Pass `tls = true` for encrypted connections on all platforms
- **Server sockets**: Accept inbound connections as a `Flow<ClientToServerSocket>`
- **Flow-based reading**: Stream data via `readFlow()` and `readFlowString()`

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/socket.svg)](https://central.sonatype.com/artifact/com.ditchoom/socket)

```kotlin
dependencies {
    implementation("com.ditchoom:socket:<latest-version>")
}
```

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/socket).

## Quick Example

```kotlin
// Client: connect, write, read, close
val socket = ClientSocket.connect(port = 443, hostname = "example.com", tls = true)
socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
val response = socket.readString()
socket.close()
```

## Server Example

```kotlin
val server = ServerSocket.allocate()
val clients = server.bind()

launch {
    clients.collect { client ->
        val message = client.readString()
        client.writeString(message) // echo
        client.close()
    }
}

// Connect a client
val client = ClientSocket.connect(server.port())
client.writeString("Hello!")
val echo = client.readString() // "Hello!"
client.close()
server.close()
```

## Lambda-Scoped Connections

For automatic resource cleanup:

```kotlin
val response = ClientSocket.connect(80, hostname = "example.com") { socket ->
    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
    socket.readString()
} // socket closed automatically
```

## Platform Support

| Platform | Implementation |
|----------|---------------|
| JVM 1.8+ | `AsynchronousSocketChannel` / `SocketChannel` |
| Android | Same as JVM (shared source set) |
| iOS/macOS/tvOS/watchOS | `NWConnection` (Network.framework) |
| Node.js | `net.Socket` |
| Browser | Not supported (throws `UnsupportedOperationException`) |

## License

    Copyright 2022 DitchOoM

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

[docs]: https://ditchoom.github.io/socket/
