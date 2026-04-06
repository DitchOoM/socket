## ConnectionContext.pool leaks direct buffers — no one calls close()

**File:** `src/commonMain/kotlin/com/ditchoom/socket/ConnectionContext.kt`

**Symptom:** OOM after ~500 connections: `Cannot reserve N bytes of direct buffer memory (allocated: 1073M, limit: 1073M)`. Each `ConnectionContext` creates a `BufferPool` that allocates direct buffers. When the connection closes, `transport.close()` closes the socket but nobody calls `context.close()` to clear the pool.

### Root cause

`TcpTransport.connect()` takes a `ConnectionContext` but doesn't own its lifecycle:

```kotlin
class TcpTransport : Transport {
    override suspend fun connect(hostname, port, context): ByteStream {
        val socket = ClientSocket.allocate()
        socket.bufferFactory = context.bufferFactory  // uses pooled factory
        socket.open(...)
        return TcpByteStream(socket)  // context is dropped — pool never cleared
    }
}
```

The returned `TcpByteStream` only holds the socket, not the context. When `TcpByteStream.close()` is called, the socket closes but `context.pool` retains direct buffers until GC.

### Fix

`TcpByteStream` should own the `ConnectionContext` and close it:

```kotlin
class TcpByteStream(
    private val socket: ClientToServerSocket,
    private val context: ConnectionContext? = null,
) : ByteStream {
    // ...
    override suspend fun close() {
        socket.close()
        context?.close()  // frees pooled direct buffers
    }
}
```

And `TcpTransport.connect()` passes it through:

```kotlin
override suspend fun connect(hostname, port, context): ByteStream {
    val socket = ClientSocket.allocate()
    socket.bufferFactory = context.bufferFactory
    socket.open(...)
    return TcpByteStream(socket, context)
}
```

This ties the pool lifetime to the ByteStream lifetime. When the consumer calls `transport.close()`, the pool is cleared and direct buffers are freed.

---

## TcpByteStream.read() returns tiny buffers — causes O(n²) in StreamProcessor

**File:** `src/commonMain/kotlin/com/ditchoom/socket/transport/TcpByteStream.kt`

**Symptom:** Autobahn case 9.6.1 (1MB binary in 64-byte compressed chunks) takes >10s in a full test suite but ~260ms in isolation. The StreamProcessor accumulates 16,384 linked nodes because each TCP read returns a tiny buffer.

### Root cause

`TcpByteStream.read()` calls `socket.read(timeout): ReadBuffer` which returns as soon as ANY data arrives — potentially 64 bytes per TCP segment. Each buffer becomes a node in the StreamProcessor's linked chain.

For a 1MB message in 64-byte TCP segments:
- 16,384 calls to `StreamProcessor.append()` → 16,384 nodes
- Every `available()` walks all 16K nodes: O(n)
- Every `readBuffer(n)` walks to find the offset: O(n)
- Total frame read: O(n²) where n = number of TCP segments

The previous working path (`SocketTransportAdapter`) used `socket.read(buffer: WriteBuffer, timeout): Int` with a caller-allocated 64KB buffer. The socket filled up to 64KB before returning → ~16 nodes for 1MB.

### Fix

Buffer's `StreamProcessor.append()` coalescing (already implemented) reduces 16K nodes to ~64 by copying small buffers into the tail. This brings O(n²) down to O(1) amortized.

Additionally, `TcpByteStream` could read into a larger pooled buffer instead of returning whatever tiny chunk the socket gives:

```kotlin
override suspend fun read(timeout: Duration): ReadResult {
    // Read into a pooled buffer (e.g. 64KB) instead of letting the socket
    // allocate a tiny buffer per TCP segment. Reduces StreamProcessor node count.
    val buffer = context?.pool?.acquire(READ_BUFFER_SIZE) ?: bufferFactory.allocate(READ_BUFFER_SIZE)
    val bytesRead = socket.read(buffer, timeout)
    if (bytesRead <= 0) {
        buffer.freeIfNeeded()
        return ReadResult.End
    }
    buffer.setLimit(buffer.position())
    buffer.position(0)
    return ReadResult.Data(buffer)
}
```

This eliminates the need for coalescing in the common case — large pooled reads produce few nodes naturally.

### Impact table

| Approach | 1MB / 64B chunks | Nodes | Complexity |
|----------|-------------------|-------|------------|
| Tiny socket reads, no coalescing | >10s (timeout) | 16,384 | O(n²) |
| Tiny socket reads + coalescing | ~260ms | ~64 | O(1) amortized |
| Large pooled reads (proposed) | ~260ms | ~16 | O(1) |
| Large pooled reads + coalescing | ~260ms | ~16 | O(1) |
