# QuicheDriver Redesign: Reactive, Zero-Copy, No Impossible States

## Problems with Current Design

### 1. Polling (1000 wakeups/second doing nothing)

```kotlin
// Current: busy-loop with delay
suspend fun driverLoop() {
    while (!closed) {
        if (clientMode) channel.read(buf)  // non-blocking, often returns -1
        while (true) { commands.tryReceive() ?: break }  // drain
        satisfyPendingReads()  // scan list
        pollNewStreams()       // call quiche
        updateState()          // call quiche
        flushOutgoing()        // call quiche
        delay(1)               // POLL: wake up 1000x/sec per connection
    }
}
```

Every connection burns CPU constantly even when idle. With 100 connections,
that's 100,000 wakeups/second doing nothing.

### 2. Data Copying (violates zero-copy principle)

```kotlin
// Current: server copies packet data before sending to driver
val pktCopy = bufferFactory.allocate(received)  // ALLOCATE
recvBB.put(pktBB)                                // COPY
driver.commands.trySend(RecvPacket(pktAddr, received, pktCopy))
```

The copy exists because the server reuses one receive buffer across all
connections. By the time the driver processes the command, the buffer may
have been overwritten.

### 3. Impossible States (mutable state that can get out of sync)

```kotlin
// Current: manual mutable state
private val pendingReads = mutableListOf<QuicheCmd.StreamRecv>()  // orphanable
private val knownStreams = mutableSetOf<Long>()                    // manually tracked
private var closed = false                                         // checked everywhere
private var nextStreamId = 0L                                      // raw counter
```

- `pendingReads` entries can be orphaned if the driver dies
- `knownStreams` duplicates quiche's internal state
- `closed` boolean is checked in multiple places, can race
- Nothing prevents calling `streamRecv` on a stream ID that doesn't exist

---

## Proposed Design

### Principle: The command channel IS the event loop

The driver blocks on `commands.receive()`. Every event is a command.
No polling. No delay. No busy-loop.

```kotlin
internal class QuicheDriver(...) {
    val commands = Channel<QuicheCmd>(Channel.UNLIMITED)

    suspend fun run() {
        try {
            for (cmd in commands) {   // BLOCKS here — zero CPU when idle
                execute(cmd)
                afterCommand()        // flush, discover streams, check state
            }
        } finally {
            cleanup()
        }
    }

    private fun afterCommand() {
        flushOutgoing()
        discoverNewStreams()
        handleTimeout()
    }
}
```

When there are no commands, the coroutine is **suspended** — zero CPU,
zero wakeups, zero allocations.

### Principle: Every event is a command

Client UDP receive is NOT done inside the driver. A separate coroutine
reads UDP and sends `RecvPacket` commands:

```kotlin
// Client: UDP reader coroutine → commands
scope.launch(Dispatchers.IO) {
    while (isActive) {
        val buf = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
        val bb = buf.directByteBuffer()
        bb.clear()
        val read = channel.read(bb)  // BLOCKS until data arrives (NIO selector)
        if (read > 0) {
            commands.send(RecvPacket(buf, read))  // transfer ownership
        } else {
            buf.freeNativeMemory()
            yield()
        }
    }
}
```

Server receive loop does the same — allocates a fresh buffer per packet,
receives into it, sends to the right driver. **Zero copy.**

Timeouts are also commands:

```kotlin
// Timeout coroutine
scope.launch {
    while (isActive) {
        val nanos = commands.sendAndAwait(GetTimeout)
        if (nanos > 0) delay(nanos.nanoseconds)
        commands.send(OnTimeout)
    }
}
```

### Principle: Zero-copy packet delivery

Server receive loop:

```kotlin
// Server: receive into fresh buffer, transfer to driver
while (!closed) {
    val buf = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
    val bb = buf.directByteBuffer()
    val peer = channel.receive(bb)   // DatagramChannel.receive into fresh buffer
    val received = bb.position()

    if (received > 0) {
        val driver = routeByDcid(buf, received)
        if (driver != null) {
            driver.commands.send(RecvPacket(buf, received))  // ownership transferred
        } else {
            val newDriver = acceptNewConnection(buf, received, peer)
            // buf ownership transferred to driver via initial connRecv
        }
    } else {
        buf.freeNativeMemory()
    }
}
```

The buffer is allocated once, written into by NIO, passed to quiche, then
freed by the driver. One allocation, zero copies.

### Principle: Stream reads are reactive, not polled

Current problem: stream reads park a `CompletableDeferred` in a list.
The driver scans the list every tick. If the driver dies, the deferreds
are orphaned.

Fix: each stream has a `Channel<Unit>(CONFLATED)` that signals when data
may be available. The stream read suspends on its signal, not on a
deferred parked in the driver.

```kotlin
// Per-stream signal managed by the driver
internal class StreamSlot(val id: QuicStreamId) {
    val dataSignal = Channel<Unit>(Channel.CONFLATED)
}

// When connReadable reports a stream, signal it
private fun discoverNewStreams() {
    val iter = api.connReadable(conn)
    while (api.streamIterNext(iter, streamIdBuf)) {
        val id = readStreamId()
        val slot = streams.getOrPut(id) { StreamSlot(id).also { emitNewStream(it) } }
        slot.dataSignal.trySend(Unit)  // wake up any pending read
    }
    api.streamIterFree(iter)
}

// Stream read: suspends on signal, then calls connStreamRecv
override suspend fun streamRead(streamId: QuicStreamId, ...): ReadResult {
    val slot = streams[streamId] ?: return ReadResult.End
    while (true) {
        // Try read immediately
        val result = requestStreamRecv(streamId, buf)
        if (result != QUICHE_ERR_DONE) return toReadResult(result, buf)
        // No data yet — wait for signal
        slot.dataSignal.receive()  // suspends until driver signals
    }
}

// requestStreamRecv sends a command to the driver and awaits result
private suspend fun requestStreamRecv(...): Long {
    val deferred = CompletableDeferred<Long>()
    driver.commands.send(StreamRecv(streamId, addr, len, deferred))
    return deferred.await()
}
```

The deferred is created per-call and completed synchronously by the driver.
No list. No scanning. No orphaning. If the driver's channel closes,
`commands.send()` throws `ClosedSendChannelException` — the stream read
fails immediately.

### Principle: No impossible states

**Stream tracking:** The `streams` map is the source of truth. A stream
exists when quiche reports it (via `connReadable`) or when the user opens
it (via `openStream` command). There's no separate `knownStreams` set.

**Lifecycle:** The driver runs as a coroutine in the connection's scope.
When the scope is cancelled:
1. `commands` channel closes
2. `for (cmd in commands)` loop ends
3. `finally` block frees all quiche resources
4. All stream signals close (pending reads get CancellationException)

No `closed` boolean. No `check(!closed)`. The channel state IS the
lifecycle state.

**Stream IDs:** The driver owns stream ID allocation. `openStream()` is
a command that returns the new stream. The caller can't manufacture a
stream ID that doesn't exist.

```kotlin
sealed interface QuicheCmd {
    // Packet delivery (zero-copy: buffer ownership transfers to driver)
    class RecvPacket(val buf: PlatformBuffer, val len: Int) : QuicheCmd

    // Stream operations (deferred completed synchronously by driver)
    class OpenStream(val result: CompletableDeferred<StreamSlot>) : QuicheCmd
    class StreamRecv(val id: Long, val addr: Long, val len: Int, val result: CompletableDeferred<Long>) : QuicheCmd
    class StreamSend(val id: Long, val addr: Long, val len: Int, val fin: Boolean, val result: CompletableDeferred<Int>) : QuicheCmd

    // Lifecycle
    class Close(val app: Boolean, val err: Long, val result: CompletableDeferred<Unit>) : QuicheCmd

    // Timeout (from timeout coroutine)
    object OnTimeout : QuicheCmd
    class GetTimeout(val result: CompletableDeferred<Long>) : QuicheCmd
}
```

---

## What buffer-codec handles

**Already generated:** `AlpnProtocol` uses `@ProtocolMessage` + `@LengthPrefixed(Byte)`
for wire encoding. The KSP processor generates `AlpnProtocolCodec`.

**Not suitable for codec:** sockaddr_in/in6 structs interact directly with native
memory addresses passed to quiche. The manual byte-level writes are already optimal
(4-5 writes per struct). Codec abstraction would add indirection without benefit.

**Not suitable for codec:** QuicheCmd sealed interface is internal command routing,
not wire protocol. No serialization needed.

**Future candidate:** If QUIC transport parameters or custom frames need Kotlin-level
parsing (not just passing through to quiche), `@ProtocolMessage` with sealed dispatch
would reduce boilerplate significantly.

---

## Architecture Diagram

```
                    ┌─────────────────────────────┐
                    │        QuicScope             │
                    │   (CoroutineScope)           │
                    │                              │
                    │  openStream() ──┐            │
                    │  acceptStream() │            │
                    │  streams()      │            │
                    └─────────────────┼────────────┘
                                      │
                              ┌───────▼────────┐
                              │ commands channel│
                              └───────┬────────┘
                                      │
            ┌─────────────────────────▼──────────────────────────┐
            │                  QuicheDriver.run()                │
            │                                                    │
            │  for (cmd in commands) {    ← BLOCKS, zero CPU     │
            │      execute(cmd)           ← single-threaded      │
            │      flushOutgoing()        ← quiche_conn_send     │
            │      discoverNewStreams()   ← quiche_conn_readable │
            │      handleTimeout()        ← quiche_conn_on_timeout│
            │  }                                                  │
            │                                                    │
            │  streams: Map<Long, StreamSlot>  ← source of truth │
            │                                                    │
            └────────────────────────────────────────────────────┘
                      ▲                            │
                      │ RecvPacket                  │ UDP send
                      │ (zero-copy)                 ▼
            ┌─────────┴──────────┐      ┌──────────────────────┐
            │  UDP Reader        │      │  DatagramChannel     │
            │  (client mode)     │      │  .write() / .send()  │
            │  or                │      └──────────────────────┘
            │  Server RecvLoop   │
            │  (allocate → recv  │
            │   → send cmd)      │
            └────────────────────┘

Stream read flow:
  stream.read()
    → commands.send(StreamRecv(..., deferred))
    → driver executes: connStreamRecv
    → if data: deferred.complete(result)
    → if DONE: driver signals slot.dataSignal when data arrives later
              stream.read() loop: await signal → retry StreamRecv
```

---

## Migration Steps

1. Add `StreamSlot` class with `dataSignal` channel
2. Rewrite `QuicheDriver.run()` as `for (cmd in commands)` loop
3. Move client UDP read into separate coroutine that sends `RecvPacket`
4. Remove `delay(1)` polling, `pendingReads` list, `closed` boolean
5. Update server receive loop: allocate fresh buffer per packet, no copy
6. Update `DriverStreamAdapter.streamRead` to use signal-based retry
7. Add `OnTimeout` / `GetTimeout` commands, move timeout to coroutine
8. Remove `knownStreams` set — use `streams` map as source of truth

---

## What Stays the Same

- `QuicScope` interface (CoroutineScope + openStream/acceptStream/streams)
- `QuicEngine.connect {}` scope-based API
- `QuicServer.connections {}` scope-based API
- `QuicStreamMux<T>` wrapping QuicScope + Codec → StreamMux<T>
- `QuicByteStream` wrapping `QuicheStreamByteStream`
- All quiche JNI/FFM bindings (QuicheApi, JniQuicheApi, FfmQuicheApi)
- SockAddrUtil, AlpnUtils (already optimal)
- Test structure (ServerConnectionTimingTest pattern)
