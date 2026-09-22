package com.ditchoom.socket.quic.trace

/** Whether a connection records a trace, and where quiche writes its qlog and TLS secrets beside it. */
sealed interface TraceCapture {
    data object Off : TraceCapture

    class On(
        val recorder: QuicTraceRecorder,
        val qlog: QlogTarget = QlogTarget.Off,
        val trafficSecrets: TrafficSecretsLog = TrafficSecretsLog.Off,
    ) : TraceCapture
}

/**
 * Run [block] against the recorder, or nothing at all when capture is off.
 *
 * `inline`, so [block] is only evaluated in the [TraceCapture.On] branch and costs neither a lambda
 * nor a call when off — the FFI reads some call sites make stay behind the branch.
 */
inline fun TraceCapture.record(block: (QuicTraceRecorder) -> Unit) {
    when (this) {
        TraceCapture.Off -> Unit
        is TraceCapture.On -> block(recorder)
    }
}

/** [record], for a call site that produces a value: [ifOff] is returned when capture is off. */
inline fun <T> TraceCapture.recordOr(
    ifOff: T,
    block: (QuicTraceRecorder) -> T,
): T =
    when (this) {
        TraceCapture.Off -> ifOff
        is TraceCapture.On -> block(recorder)
    }
