package com.ditchoom.socket.quic.trace

/**
 * Where recorded trace events go. The recorder never touches files or sockets — the **consumer**
 * owns IO (append to a file, ship over the network, buffer in memory), which keeps the recorder
 * platform-free and `ByteArray`-free. [emit] receives the **typed** [TraceEvent] (its `v1` string
 * form is [TraceEvent.toString], so a file/network sink just emits `event.toString()`); the string
 * is only the serialization boundary, never the internal model. [emit] may be invoked from multiple
 * coroutines (the driver loop, per-path reader loops, monitor collectors); implementations must
 * tolerate concurrent calls the same way a log sink does.
 *
 * Lives in `:socket-quic` (not `:socket-quic-quiche`) so the public [com.ditchoom.socket.quic.QuicOptions]
 * capture opt-in ([com.ditchoom.socket.quic.trace.QuicTraceCapture]) can reference it while the
 * quiche-backed `QuicTraceRecorder` — one module downstream — records through the same type.
 */
fun interface TraceSink {
    fun emit(event: TraceEvent)
}
