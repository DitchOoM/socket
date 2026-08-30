@file:OptIn(ExperimentalDatagramApi::class, InternalQuicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.MAX_UDP_DATAGRAM_SIZE
import kotlin.test.fail

/** What the scripted opener does with the route probe. The path socket is always served for real. */
internal sealed interface ProbeAnswer {
    /** `UdpSocket.connect` refuses the probe — no descriptors, a sandbox, no route. */
    data class Refuse(
        val cause: Throwable,
    ) : ProbeAnswer

    /** The probe connects and reports [localAddress] — including the states no real host offers. */
    data class Report(
        val localAddress: LocalAddress,
    ) : ProbeAnswer

    /** The probe is served by the platform, exactly as in production. */
    data object Serve : ProbeAnswer
}

/**
 * The [ConnectedUdpOpener] seam's shared fake: serves the socket a caller actually asked for and scripts
 * only the route probe, telling the two apart by destination port — the probe never goes to [peerPort]
 * (that is #483's fix) and the real open always does. So a refusal here is a refusal of the probe alone,
 * and the connect the wildcard fallback used to reach is genuinely available for the caller to take.
 *
 * Top-level rather than nested in one test class because two suites drive the same seam for different
 * reasons: [RouteSourceResolutionTests] for what `openPath` does with each [RouteProbeFailure] (#523),
 * and [PrimaryPathSourceAddressTests] for the fact that the connection's *primary* socket is bound the
 * same way (#519). The seam exists at all because none of these conditions — no descriptors, a sandbox,
 * a `getsockname` that fails — can be provoked on a real host on demand, and a decision no test can
 * drive is how #482 lived unnoticed under a green suite.
 */
internal class ScriptedOpener(
    private val peerPort: Int,
    private val probeAnswer: ProbeAnswer,
) : ConnectedUdpOpener {
    /** Destination ports the route probe was pointed at, in order. */
    val probes = mutableListOf<Int>()

    /** The `localHost` each *path* open asked to bind, in order — `null` is the unnamed bind. */
    val pathBinds = mutableListOf<String?>()

    /** Probe channels handed out, so a test can assert the probe was closed. */
    val probeChannels = mutableListOf<ScriptedChannel>()

    override suspend fun open(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): ConnectedDatagramChannel {
        if (remotePort == peerPort) {
            pathBinds += localHost
            return ConnectedUdpOpener.Platform.open(
                remoteHost,
                remotePort,
                localHost,
                localPort,
                receiveBufferSize,
                bufferFactory,
            )
        }
        probes += remotePort
        return when (probeAnswer) {
            is ProbeAnswer.Refuse -> throw probeAnswer.cause
            is ProbeAnswer.Report ->
                ScriptedChannel(SocketAddress.ofLiteral(remoteHost, remotePort), probeAnswer.localAddress)
                    .also { probeChannels += it }

            ProbeAnswer.Serve ->
                ConnectedUdpOpener.Platform.open(
                    remoteHost,
                    remotePort,
                    localHost,
                    localPort,
                    receiveBufferSize,
                    bufferFactory,
                )
        }
    }
}

/** A connected channel that only answers [localAddress] — the one thing the route probe reads. */
internal class ScriptedChannel(
    override val peer: SocketAddress,
    override val localAddress: LocalAddress,
) : ConnectedDatagramChannel {
    var closed = false
        private set

    override val isOpen: Boolean get() = !closed
    override val capabilities: DatagramCapabilities = DatagramCapabilities.None
    override val maxWritableSize: Int = MAX_UDP_DATAGRAM_SIZE

    override suspend fun receive(): DatagramReadResult = DatagramReadResult.Closed()

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) = fail("the route probe must never send: a connected UDP socket fixes the 4-tuple and nothing else")

    override fun close() {
        closed = true
    }
}
