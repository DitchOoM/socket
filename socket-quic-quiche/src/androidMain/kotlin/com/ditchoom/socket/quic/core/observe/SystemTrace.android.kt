package com.ditchoom.socket.quic.core.observe

import android.os.Build
import android.os.Trace

/**
 * `Trace.isEnabled()` needs API 29; below it the system's own check inside `beginSection` is the gate, which is one
 * read of a flag too.
 */
internal actual fun traceBegin(section: TraceSection): TraceMark =
    if (Build.VERSION.SDK_INT < 29 || Trace.isEnabled()) {
        Trace.beginSection(section.label)
        TraceMark.Opened
    } else {
        TraceMark.Skipped
    }

internal actual fun traceEnd(mark: TraceMark) =
    when (mark) {
        TraceMark.Opened -> Trace.endSection()
        TraceMark.Skipped -> Unit
    }
