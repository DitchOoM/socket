## `read(timeout)` TLS path returns empty buffer — data silently discarded

**File:** `src/commonJvmMain/kotlin/com/ditchoom/socket/nio2/AsyncBaseClientSocket.kt` (line 28)
**Related:** `src/commonJvmMain/kotlin/com/ditchoom/socket/JvmTlsHandler.kt` (`slicePlainText`, line 221)

### Symptom

Any TLS connection using `socket.read(timeout): ReadBuffer` hangs until timeout.
The data is decrypted successfully but returned with `remaining() = 0`, so callers
(e.g., `StreamProcessor.append()`) discard it.

Plaintext connections work correctly after the `resetForRead()` fix.

### Root cause

`JvmTlsHandler.unwrap()` returns buffers via `slicePlainText()` in a **write-mode convention**
where `position` encodes the data size:

```kotlin
// JvmTlsHandler.kt:221
private fun slicePlainText(plainText: BaseJvmBuffer): PlatformBuffer {
    val position = plainText.position()
    plainText.position(0)
    plainText.setLimit(position)
    val slicedBuffer = plainText.slice()
    slicedBuffer.position(slicedBuffer.limit())  // ← position = limit, remaining() = 0
    return slicedBuffer
}
```

The `read(buffer: WriteBuffer, timeout)` overload (line 59) knows about this convention — it
reads data size from `position()`, then flips:

```kotlin
val decrypted = tls.unwrap(timeout)
val bytesAvailable = decrypted.position()  // reads size from position
decrypted.resetForRead()                    // flips: position=0, limit=data_size
buffer.write(decrypted)
```

But `read(timeout): ReadBuffer` (line 28) returns the buffer directly:

```kotlin
tlsHandler?.let { return it.unwrap(timeout) }  // remaining() = 0, data lost
```

### Fix

Option A — flip in `read(timeout)` (minimal change):

```kotlin
// AsyncBaseClientSocket.kt:28
tlsHandler?.let {
    val decrypted = it.unwrap(timeout)
    if (decrypted is PlatformBuffer) decrypted.resetForRead()
    return decrypted
}
```

This works because `resetForRead()` (flip) sets `limit = position` (= data_size) and
`position = 0`, producing `remaining() = data_size`.

Option B — fix `slicePlainText` to return read-ready buffers, update `read(buffer, timeout)`:

```kotlin
// JvmTlsHandler.kt - slicePlainText
private fun slicePlainText(plainText: BaseJvmBuffer): PlatformBuffer {
    plainText.resetForRead()  // position=0, limit=data_end
    return plainText.slice()
}

// AsyncBaseClientSocket.kt - read(buffer, timeout) TLS path
val decrypted = tls.unwrap(timeout)
val bytesAvailable = decrypted.remaining()  // was: decrypted.position()
if (bytesAvailable > 0) {
    buffer.write(decrypted)                  // already in read mode, no flip needed
}
return bytesAvailable
```

Option B is cleaner — buffers returned from public methods should be read-ready.

### Verified

- Non-TLS TCP: 4/4 pass (hivemq v4+v5, mosquitto v4+v5)
- TLS TCP: 5/5 fail with `InterruptedByTimeoutException` (15s read timeout, data decrypted but discarded)
