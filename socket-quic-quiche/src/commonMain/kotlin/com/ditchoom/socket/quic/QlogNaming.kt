package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.trace.record

/**
 * Names a core connection's qlog: the capture's own [QlogTarget.File] when it named one, else one file per connection
 * in the `QUIC_QLOG_DIR` directory (JVM/Android: the `quic.qlog.dir` property first), `quiche-<role>-<session>.sqlog`,
 * else none. Runs before the connection's first packet in either direction, on the thread that made it. A path quiche
 * cannot open, and a [QlogTarget.Budgeted] target, which a core connection does not write, are recorded in the trace
 * as refused, never fatal.
 */
internal fun QuicheApi.nameQlog(
    conn: QuicheConn,
    capture: TraceCapture,
    role: QuicRole,
    session: QuicSessionId,
) {
    val named =
        when (capture) {
            TraceCapture.Off -> QlogTarget.Off
            is TraceCapture.On -> capture.qlog
        }
    val path =
        when (named) {
            is QlogTarget.File -> named.path
            is QlogTarget.Budgeted -> return refused(capture, "${named.directory.path.trimEnd('/')}/${named.name}.sqlog", role, session)
            QlogTarget.Off ->
                when (val dir = environmentSetting("quic.qlog.dir", "QUIC_QLOG_DIR")) {
                    EnvironmentSetting.Unset -> return
                    is EnvironmentSetting.Value -> "${dir.text.trimEnd('/')}/quiche-${role.label}-$session.sqlog"
                }
        }
    if (!connSetQlogPath(conn, path, "ditchoom-socket ${role.label}", "session $session")) refused(capture, path, role, session)
}

private fun refused(
    capture: TraceCapture,
    path: String,
    role: QuicRole,
    session: QuicSessionId,
) {
    println("[qlog] refused $path for ${role.label} connection $session")
    capture.record { it.qlogRefused(path) }
}
