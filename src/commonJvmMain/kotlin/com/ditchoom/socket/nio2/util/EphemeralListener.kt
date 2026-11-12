package com.ditchoom.socket.nio2.util

import com.ditchoom.socket.SocketException
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_EPHEMERAL_ATTEMPTS = 16
private val LOOPBACK_PROOF_TIMEOUT = 500.milliseconds

/** Whether a connection to a listener's port on IPv4 loopback reaches that listener. */
internal sealed interface LoopbackReach {
    /** The connection was queued on the listener. */
    data object Reaches : LoopbackReach

    /** The connection completed, but another socket owns that port on IPv4 and took it. */
    data object Intercepted : LoopbackReach

    /** No connection could be made to prove either way, as on a host without IPv4 loopback. */
    data object Unverifiable : LoopbackReach
}

/**
 * Binds a listener on every interface at an ephemeral port that IPv4 loopback clients actually reach.
 *
 * On Darwin a dual-stack wildcard listener does not own the IPv4 half of its port. The OS draws the port
 * from the IPv6 table, so it can hand out a port another process already holds on `127.0.0.1` or
 * `0.0.0.0`; the bind succeeds and every IPv4 connection then completes against the other socket while
 * this listener stays silent. So each candidate is proven by connecting to it over IPv4 loopback and
 * accepting that connection; a candidate that is [LoopbackReach.Intercepted] is closed and another is
 * drawn. The proof connection is consumed here and never reaches the caller. Other platforms refuse such
 * a bind themselves, so there the proof always succeeds at once.
 *
 * [bind] opens one candidate and [dial] names the address the proof connects to; both are parameters so a
 * test can stand a stranger in front of a candidate.
 *
 * @throws SocketIOException when [MAX_EPHEMERAL_ATTEMPTS] candidates were all intercepted.
 */
internal suspend fun bindEphemeralListener(
    backlog: Int,
    bind: () -> AsynchronousServerSocketChannel = { AsynchronousServerSocketChannel.open().bind(null, backlog) },
    dial: (AsynchronousServerSocketChannel) -> InetSocketAddress = ::loopbackAddressOf,
): AsynchronousServerSocketChannel {
    repeat(MAX_EPHEMERAL_ATTEMPTS) {
        val candidate = bind()
        when (candidate.loopbackReach(dial(candidate))) {
            LoopbackReach.Reaches, LoopbackReach.Unverifiable -> return candidate
            LoopbackReach.Intercepted -> candidate.blockingClose()
        }
    }
    throw SocketIOException("No ephemeral port reachable over IPv4 loopback after $MAX_EPHEMERAL_ATTEMPTS draws")
}

internal suspend fun AsynchronousServerSocketChannel.loopbackReach(dial: InetSocketAddress): LoopbackReach {
    val client = asyncSocket()
    try {
        try {
            client.aConnect(dial, LOOPBACK_PROOF_TIMEOUT)
        } catch (e: SocketException) {
            return LoopbackReach.Unverifiable
        } catch (e: TimeoutCancellationException) {
            return LoopbackReach.Unverifiable
        }
        return try {
            withTimeout(LOOPBACK_PROOF_TIMEOUT) { aAccept() }.blockingClose()
            LoopbackReach.Reaches
        } catch (e: TimeoutCancellationException) {
            LoopbackReach.Intercepted
        }
    } finally {
        client.blockingClose()
    }
}

private fun loopbackAddressOf(listener: AsynchronousServerSocketChannel) =
    InetSocketAddress(InetAddress.getByName("127.0.0.1"), (listener.localAddress as InetSocketAddress).port)
