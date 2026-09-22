package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.testkit.trace.TraceSink as NeutralTraceSink

/**
 * Where one connection's qlog goes — the frame-level record quiche writes itself
 * (`quiche_conn_set_qlog_path`), beside the replay trace this library writes.
 *
 * quiche opens the file with `create_new`, so the path must be **unique per connection**: a repeated
 * name is refused silently and that connection has no qlog. Name it from something the consumer
 * owns and never reuses — a per-connection sequence number, the same one its replay trace carries —
 * not from a native handle, which the allocator hands to the next connection as soon as this one is
 * freed.
 */
sealed interface QlogTarget {
    /** No qlog for this connection (a `QUIC_QLOG_DIR` environment, if any, still applies). */
    data object Off : QlogTarget

    /** quiche creates and writes [path], one file for the connection's whole life; the directory must already exist. */
    data class File(
        val path: String,
    ) : QlogTarget

    /**
     * quiche writes the connection's qlog as a run of segments in [directory], named from [name]
     * (`<name>.sqlog`, then `<name>_seg0002.sqlog` and on) and kept or dropped under the directory's
     * [QlogBudget]. [name] must be unique per connection, as a [File] path must.
     */
    data class Budgeted(
        val directory: QlogDirectory,
        val name: String,
    ) : QlogTarget
}

/**
 * Everything a [QuicTraceCapture] mints for **one** connection: the [sink] its replay trace is
 * written to and the [qlog] quiche writes for it. One product per connection so the two records
 * are named together and can be paired afterwards — `conn-0007.trace` beside `conn-0007.sqlog`.
 */
data class QuicConnectionCapture(
    val sink: NeutralTraceSink,
    val qlog: QlogTarget = QlogTarget.Off,
)
