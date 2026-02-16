---
sidebar_position: 4
title: Socket Options
---

# Socket Options

The `SocketOptions` data class allows you to configure TCP socket behavior and TLS.

## Available Options

```kotlin
data class SocketOptions(
    val tcpNoDelay: Boolean? = null,     // disable Nagle's algorithm
    val reuseAddress: Boolean? = null,   // allow address reuse
    val keepAlive: Boolean? = null,      // enable TCP keep-alive
    val receiveBuffer: Int? = null,      // receive buffer size in bytes
    val sendBuffer: Int? = null,         // send buffer size in bytes
    val tls: TlsConfig? = null,         // TLS configuration (null = plaintext)
)
```

## Usage

Pass `SocketOptions` when connecting:

```kotlin
val options = SocketOptions(
    tcpNoDelay = true,       // low-latency, disable batching
    keepAlive = true,        // detect dead connections
    receiveBuffer = 65536,   // 64KB receive buffer
    sendBuffer = 65536,      // 64KB send buffer
)

val socket = ClientSocket.connect(
    port = 80,
    hostname = "example.com",
    socketOptions = options,
)
```

## Presets

```kotlin
// Low-latency plaintext (tcpNoDelay = true)
SocketOptions.LOW_LATENCY

// TLS with low latency and default certificate validation
SocketOptions.tlsDefault()

// TLS with all validation disabled (development only)
SocketOptions.tlsInsecure()
```

## Option Details

### `tcpNoDelay`

Disables Nagle's algorithm. When `true`, small packets are sent immediately without waiting to batch them. Use for latency-sensitive protocols (e.g., interactive sessions, real-time messaging).

### `reuseAddress`

Allows binding to an address that is in `TIME_WAIT` state. Useful for servers that need to restart quickly.

### `keepAlive`

Enables TCP keep-alive probes. The OS will periodically send probes on idle connections to detect if the remote peer is still reachable.

### `receiveBuffer` / `sendBuffer`

Sets the OS-level socket buffer sizes. Larger buffers can improve throughput for high-bandwidth connections but use more memory.

### `tls`

Configures TLS for the connection. Set to a `TlsConfig` to enable TLS, or leave as `null` (the default) for plaintext. See [TLS/SSL](./tls.md) and [TLS Support Matrix](./tls-support-matrix.md) for details.
