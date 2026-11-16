package com.ditchoom.socket.quic.core.observe

/** The stretches of a loop's work a system trace names. Each [label] is a constant. */
internal enum class TraceSection(
    val label: String,
) {
    LoopPass("quic.loop.pass"),
    PollWait("quic.poll.wait"),
    ReceiveBatch("quic.recv.batch"),
    Demux("quic.demux"),
    ConnWork("quic.conn.work"),
    ConnReceive("quic.conn.receive"),
    SendBatch("quic.send.batch"),
}

/** Whether [traceBegin] opened a section, so [traceEnd] closes exactly what was opened. */
internal enum class TraceMark { Opened, Skipped }

/**
 * Opens [section] in the platform's system trace when that trace is recording; otherwise does nothing and allocates
 * nothing. Only Android has one to write to.
 */
internal expect fun traceBegin(section: TraceSection): TraceMark

/** Closes what [traceBegin] opened. */
internal expect fun traceEnd(mark: TraceMark)

/** Runs [block] as [section] of the system trace. */
internal inline fun <T> traced(
    section: TraceSection,
    block: () -> T,
): T {
    val mark = traceBegin(section)
    try {
        return block()
    } finally {
        traceEnd(mark)
    }
}
