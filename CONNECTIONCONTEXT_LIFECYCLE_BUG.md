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
