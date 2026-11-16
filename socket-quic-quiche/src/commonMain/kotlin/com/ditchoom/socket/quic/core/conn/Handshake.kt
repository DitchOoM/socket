package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes

internal enum class HandshakeStep { Handshaking, JustEstablished, Established }

private enum class Phase { Handshaking, Established }

/** The handshake's phase and the report of its end. */
@Confined
internal class Handshake(
    private val q: QuicheConnRef,
    private val startedNanos: Long,
    private val alpn: NativeBytes,
) {
    private var phase = Phase.Handshaking

    fun step(): HandshakeStep =
        when (phase) {
            Phase.Established -> HandshakeStep.Established
            Phase.Handshaking ->
                if (q.api.connIsEstablished(q.handle)) {
                    phase = Phase.Established
                    HandshakeStep.JustEstablished
                } else {
                    HandshakeStep.Handshaking
                }
        }

    /** Once, after [step] answered [HandshakeStep.JustEstablished]: the negotiated ALPN is copied into its scratch. */
    fun report(now: Nanos): ConnectReport =
        ConnectReport(now.value - startedNanos, q.api.connApplicationProto(q.handle, alpn.address, alpn.capacity))
}
