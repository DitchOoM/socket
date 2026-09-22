package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.quic.trace.record

/**
 * The directory the `QUIC_KEYLOG_DIR` environment (JVM/Android: the `quic.keylog.dir` system property
 * first, as for qlog) names for per-connection TLS key logs.
 */
internal fun keyLogDirectory(): EnvironmentSetting = environmentSetting("quic.keylog.dir", "QUIC_KEYLOG_DIR")

/**
 * Name [conn]'s TLS key log: the capture's own [TrafficSecretsLog] when it named one, else the
 * `QUIC_KEYLOG_DIR` door (`quiche-<role>-<session>.keys`), else none. Opt-in only, because the file
 * decrypts the connection. Must run before the connection's first packet in either direction, or the
 * handshake's secrets are never written. A file quiche cannot open is recorded in the trace, never fatal.
 */
internal fun QuicheApi.nameTrafficSecretsLog(
    conn: QuicheConn,
    capture: TraceCapture,
    role: QuicRole,
    session: () -> QuicSessionId,
) {
    val named =
        when (capture) {
            TraceCapture.Off -> TrafficSecretsLog.Off
            is TraceCapture.On -> capture.trafficSecrets
        }
    val target =
        when (named) {
            is TrafficSecretsLog.File -> named
            TrafficSecretsLog.Off ->
                when (val dir = keyLogDirectory()) {
                    EnvironmentSetting.Unset -> TrafficSecretsLog.Off
                    is EnvironmentSetting.Value -> TrafficSecretsLog.File("${dir.text.trimEnd('/')}/quiche-${role.label}-${session()}.keys")
                }
        }
    when (target) {
        TrafficSecretsLog.Off -> Unit
        is TrafficSecretsLog.File ->
            if (connSetKeylogPath(conn, target.path)) {
                println("[keylog] ${role.label} connection ${session()}'s TLS secrets go to ${target.path}")
            } else {
                println("[keylog] could not open ${target.path} for ${role.label} connection ${session()}")
                capture.record { it.trafficSecretsRefused(target.path) }
            }
    }
}
