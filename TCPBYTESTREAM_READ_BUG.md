## TcpByteStream.read() exhausts direct buffer memory on high message volume

**File:** `src/commonMain/kotlin/com/ditchoom/socket/transport/TcpByteStream.kt`

**Status:** Buffer flip bug fixed. New issue: direct memory exhaustion.

### Failing cases (Autobahn via websocket integration tests)

| Case | Description | Issue |
|------|-------------|-------|
| 12.1.4 | 1000 compressed msgs, 1024B each | OOM: direct buffer exhausted |
| 12.1.5 | 1000 compressed msgs, 4096B each | OOM: direct buffer exhausted |
| 12.1.6 | 1000 compressed binary msgs, 8192B each | OOM: direct buffer exhausted |
| 12.1.7 | 1000 compressed binary msgs, 16384B each | OOM: direct buffer exhausted |

All other 513 Autobahn cases pass (including 10.1.1 which is now fixed).

### Root cause

```
java.lang.OutOfMemoryError: Cannot reserve 1199746 bytes of direct buffer memory
  (allocated: 1073281671, limit: 1073741824)
```

`TcpByteStream.read()` calls `socket.read(timeout): ReadBuffer` which allocates a fresh direct buffer per read. Over 1000 messages, these buffers accumulate faster than GC can reclaim them, exhausting the 1GB direct memory limit.

The previous working path (`SocketTransportAdapter`) used `socket.read(buffer: WriteBuffer, timeout): Int` with a caller-allocated pooled buffer that was reused across reads. No direct memory pressure.

### Fix options

1. **Use BufferPool in TcpByteStream** — acquire from pool, return after StreamProcessor copies:
   ```kotlin
   override suspend fun read(timeout: Duration): ReadResult {
       val buffer = pool.acquire(readBufferSize)
       val bytesRead = socket.read(buffer, timeout)
       buffer.resetForRead()
       return ReadResult.Data(buffer) // caller frees via pool or freeIfNeeded()
   }
   ```

2. **Use managed (heap) buffers instead of direct** — avoids direct memory limit entirely but loses zero-copy NIO advantage.

3. **Make socket.read(timeout) use pooled buffers internally** — the `AsyncBaseClientSocket.read(timeout)` allocates via `bufferFactory`. If `bufferFactory` is set to a pooled factory, buffers are reused.

Option 3 is cleanest — `TcpTransport` already sets `socket.bufferFactory = options.bufferFactory` which can be a `PooledBufferFactory`. Verify that `AsyncBaseClientSocket.read(timeout)` uses `bufferFactory` (not `BufferFactory.Default`).

### Repro

```bash
# Start Autobahn with enough memory
docker run -d --name fuzzingserver -p 9001:9001 --memory=8g \
  -v $(pwd)/.docker/config:/config \
  crossbario/autobahn-testsuite \
  wstest -m fuzzingserver -s /config/fuzzingserver.json

# From ../websocket
./gradlew jvmTest -PintegrationTests \
  --tests "com.ditchoom.websocket.AutobahnCase12CompressionTests" --rerun-tasks
```
