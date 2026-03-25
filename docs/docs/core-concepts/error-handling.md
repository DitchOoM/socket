---
sidebar_position: 5
title: Error Handling
---

# Error Handling

All socket errors are thrown as subtypes of the `SocketException` sealed hierarchy. This lets you catch errors at the granularity you need — broad categories or specific failure modes.

## Exception Hierarchy

```
sealed SocketException
├── sealed SocketClosedException          — connection is gone
│   ├── ConnectionReset                   — peer sent RST (ECONNRESET)
│   ├── BrokenPipe                        — wrote to closed peer (EPIPE)
│   ├── EndOfStream                       — clean EOF (peer closed gracefully)
│   └── General                           — closed, reason not categorized
├── sealed SocketConnectionException      — failed to connect
│   ├── Refused                           — nothing listening (ECONNREFUSED)
│   ├── NetworkUnreachable                — no route to network (ENETUNREACH)
│   └── HostUnreachable                   — no route to host (EHOSTUNREACH)
├── SocketUnknownHostException            — DNS resolution failed
├── SocketTimeoutException                — connect/read/write timed out
├── SocketIOException                     — generic I/O error (catch-all)
└── sealed SSLSocketException             — TLS/SSL errors
    ├── SSLHandshakeFailedException       — certificate or handshake failure
    └── SSLProtocolException              — other TLS protocol errors
```

## Catching Errors

Use the sealed parent types to catch broad categories, or specific subtypes for fine-grained handling:

```kotlin
try {
    val socket = ClientSocket.connect(443, "example.com", socketOptions = SocketOptions.tlsDefault())
    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
    val response = socket.readString()
    socket.close()
} catch (e: SocketClosedException) {
    // Connection lost — peer closed, reset, or broken pipe
    println("Connection lost: ${e.message}")
} catch (e: SocketConnectionException) {
    // Could not connect — refused, unreachable
    println("Cannot connect: ${e.message}")
} catch (e: SocketUnknownHostException) {
    // DNS failure
    println("Unknown host: ${e.hostname}")
} catch (e: SocketTimeoutException) {
    // Timed out
    println("Timed out: ${e.message}")
} catch (e: SSLSocketException) {
    // TLS error — bad certificate, protocol mismatch
    println("TLS error: ${e.message}")
}
```

Or catch the sealed parent for simple error handling:

```kotlin
try {
    ClientSocket.connect(port, hostname) { socket ->
        socket.writeString("hello")
        socket.readString()
    }
} catch (e: SocketException) {
    println("Socket error: ${e.message}")
}
```

## Specific Subtypes

### Connection Refused

Thrown when nothing is listening on the target port. On `Refused`, the `platformError` field contains the OS-level error description:

```kotlin
try {
    ClientSocket.connect(9999, "127.0.0.1") { }
} catch (e: SocketConnectionException.Refused) {
    println("Refused: ${e.platformError}") // e.g. "Connection refused (errno=111)"
}
```

### End of Stream

Thrown when the peer closes the connection gracefully and you attempt to read:

```kotlin
try {
    val data = socket.read()
} catch (e: SocketClosedException.EndOfStream) {
    println("Peer closed the connection")
}
```

### DNS Failure

The `hostname` field preserves the hostname that failed:

```kotlin
try {
    ClientSocket.connect(80, "nonexistent.example.invalid") { }
} catch (e: SocketUnknownHostException) {
    println("Cannot resolve: ${e.hostname}")
}
```

## Platform Mapping

Each platform maps its native errors to the same sealed hierarchy:

| Platform | Connection Refused | Connection Reset | Broken Pipe | Timeout | DNS Failure | TLS Error |
|---|---|---|---|---|---|---|
| **Linux** | `ECONNREFUSED` | `ECONNRESET` | `EPIPE` | `ETIMEDOUT` | `getaddrinfo` | OpenSSL |
| **JVM** | `ConnectException` | `IOException("reset")` | `IOException("Broken pipe")` | `SocketTimeoutException` | `UnknownHostException` | `SSLException` |
| **Node.js** | `ECONNREFUSED` | `ECONNRESET` | `EPIPE` | `ETIMEDOUT` | `getaddrinfo` | `ERR_TLS` |
| **Apple** | POSIX "refused" | POSIX "reset" | POSIX "broken pipe" | POSIX "timed out" | DNS error type | TLS error type |

## Reconnection

The library provides composable reconnection primitives for protocol libraries (WebSocket, MQTT) and application code.

### ReconnectionClassifier

A `fun interface` that classifies errors as recoverable or non-recoverable:

```kotlin
val classifier = DefaultReconnectionClassifier()
when (val decision = classifier.classify(error)) {
    is ReconnectDecision.RetryAfter -> delay(decision.delay)
    is ReconnectDecision.GiveUp -> break
}
```

### DefaultReconnectionClassifier

Provides exponential backoff (100ms → 15s, 2x factor) and classifies these errors as non-recoverable:

- `SSLHandshakeFailedException` — certificate or handshake failure
- `SSLProtocolException` — TLS misconfiguration
- `SocketUnknownHostException` — DNS resolution failed

All other errors (connection refused, reset, timeout, I/O) are recoverable. Call `reset()` when a connection succeeds to restart the backoff sequence.

### Custom Classifiers

Protocol libraries add domain-specific knowledge by delegating to `DefaultReconnectionClassifier`:

```kotlin
class MyProtocolClassifier(
    private val delegate: DefaultReconnectionClassifier = DefaultReconnectionClassifier(),
) : ReconnectionClassifier {
    override suspend fun classify(error: Throwable) = when (error) {
        is MyProtocolException.AuthFailed -> ReconnectDecision.GiveUp
        is MyProtocolException.TransportFailed -> delegate.classify(error.cause!!)
        else -> delegate.classify(error)
    }
}
```

The `suspend` modifier enables network-aware reconnection — a classifier can suspend until connectivity changes before returning `RetryAfter`.

## JVM Interop

On JVM and Android, `SocketException` extends `java.io.IOException` (via `PlatformIOException`), so existing `catch (e: IOException)` blocks will catch socket errors — matching standard Java convention.
