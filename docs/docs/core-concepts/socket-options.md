---
sidebar_position: 4
title: Socket Options
---

# Socket Options

The `SocketOptions` data class allows you to configure TCP socket behavior.

## Available Options

```kotlin
data class SocketOptions(
    val tcpNoDelay: Boolean? = null,     // disable Nagle's algorithm
    val reuseAddress: Boolean? = null,   // allow address reuse
    val keepAlive: Boolean? = null,      // enable TCP keep-alive
    val receiveBuffer: Int? = null,      // receive buffer size in bytes
    val sendBuffer: Int? = null,         // send buffer size in bytes
)
```

## Usage

Pass `SocketOptions` when configuring sockets that need TCP tuning:

```kotlin
val options = SocketOptions(
    tcpNoDelay = true,       // low-latency, disable batching
    keepAlive = true,        // detect dead connections
    receiveBuffer = 65536,   // 64KB receive buffer
    sendBuffer = 65536,      // 64KB send buffer
)
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
