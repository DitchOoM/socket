package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * The cross-backend contract of #534: a `UdpSocket.connect` refused because its requested local
 * endpoint is held by another socket fails with [UdpConnectException] whose reason is
 * [UdpConnectError.AddressInUse] — on every backend that binds the requested endpoint.
 *
 * Shared body, one platform member each (JVM, Linux, Node): the held socket is a connected channel
 * on an ephemeral port, and the second connect names that port as its own. `EADDRINUSE` at the
 * bind is the one refusal a loopback host produces on demand with nothing else in play, and it is
 * the refusal the route probe (#434/#523/#547) needs to tell apart from "no descriptor left".
 *
 * Not on Apple: Network.framework treats the requested local endpoint as advisory, so the second
 * connect there succeeds on another port — [UdpConnectError.PlatformError] is Apple's member and is
 * reached only by a connection NW itself fails.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertConnectOnAHeldLocalPortReportsAddressInUse() {
    val held = UdpSocket.connect(LOOPBACK, DISCARD_PORT, LOOPBACK, 0)
    try {
        val port = held.localAddress.orNull()?.port ?: fail("the held socket reports no local port")
        val refusal =
            try {
                UdpSocket.connect(LOOPBACK, DISCARD_PORT, LOOPBACK, port).also { it.close() }
                fail("a connect that binds $LOOPBACK:$port while another socket holds it must be refused")
            } catch (e: UdpConnectException) {
                e
            }
        val reason = assertIs<UdpConnectError.AddressInUse>(refusal.error, "the refusal must be typed, got ${refusal.error}")
        // The POSIX backends carry the errno; the JVM and Node reduce it to a type/name first.
        if (reason.errno != ERRNO_NOT_SURFACED && reason.errno <= 0) {
            fail("a surfaced errno must be positive: ${reason.errno}")
        }
    } finally {
        held.close()
    }
}

private const val LOOPBACK = "127.0.0.1"

/** RFC 863 discard: a connected UDP socket sends nothing, so the port is only named. */
private const val DISCARD_PORT = 9
