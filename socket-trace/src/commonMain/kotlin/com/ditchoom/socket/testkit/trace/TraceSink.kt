package com.ditchoom.socket.testkit.trace

/**
 * Where recorded trace events go. The recorder never touches files or sockets — the **consumer**
 * owns IO (append to a file, ship over the network, buffer in memory), which keeps the recorder
 * platform-free and `ByteArray`-free. [emit] receives the **typed** [TraceEvent] (its `v1` string
 * form is [TraceEvent.toString], so a file/network sink just emits `event.toString()`); the string
 * is only the serialization boundary, never the internal model. [emit] may be invoked from multiple
 * coroutines (the driver loop, per-path reader loops, monitor collectors); implementations must
 * tolerate concurrent calls the same way a log sink does.
 *
 * Lives in the transport-neutral `:socket-testkit` (no QUIC dependency) so every stack — TCP, UDP,
 * QUIC — and any external KMP consumer can record and replay through the same type. The QUIC
 * capture opt-in (`QuicOptions.trace` / `QuicTraceCapture`, in `:socket-quic`) and the quiche-backed
 * `QuicTraceRecorder` (in `:socket-quic-quiche`) both record through this sink, downstream of here.
 */
fun interface TraceSink {
    fun emit(event: TraceEvent)
}
