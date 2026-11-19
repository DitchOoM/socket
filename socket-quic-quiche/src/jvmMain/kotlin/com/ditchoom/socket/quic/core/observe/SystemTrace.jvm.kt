package com.ditchoom.socket.quic.core.observe

internal actual fun traceBegin(section: TraceSection): TraceMark = TraceMark.Skipped

internal actual fun traceEnd(mark: TraceMark) = Unit
