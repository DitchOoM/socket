package com.ditchoom.socket

import com.ditchoom.socket.nio2.util.aAccept
import com.ditchoom.socket.nio2.util.aBind
import com.ditchoom.socket.nio2.util.openAsyncServerSocketChannel
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import kotlin.test.Test
import kotlin.test.fail

/**
 * Closing a server socket must look like a shutdown to the accept loop regardless of *when* the
 * close lands, because the JDK reports the two orderings with two different exception types:
 *
 *  - close **during** a pending accept -> [AsynchronousCloseException]
 *  - close **before** the next accept is issued -> plain [ClosedChannelException]
 *
 * [AsynchronousCloseException] is a subclass of [ClosedChannelException], so code matching only the
 * subclass silently handles the first ordering and mishandles the second. That is what shipped: the
 * second ordering fell through to `wrapJvmException` and surfaced as
 * `SocketClosedException.General("Socket is closed")` out of a perfectly normal shutdown — which is
 * how `ErrorHandlingTests.zeroLengthWrite` failed in CI.
 *
 * Only the already-closed ordering is pinned here: it is the one that regressed, and it is the one
 * that discriminates. A test asserting the close-during-accept ordering would pass with or without
 * the fix, so it would not be a gate.
 */
class AcceptLoopCloseOrderingTests {
    @Test
    fun acceptOnAnAlreadyClosedChannelReportsCloseNotFailure() =
        runTest {
            val channel = openAsyncServerSocketChannel()
            channel.aBind(InetSocketAddress("localhost", 0), 0)

            // Close *between* accepts — nothing is pending, so the next accept hits an
            // already-closed channel and the JDK raises a plain ClosedChannelException rather
            // than AsynchronousCloseException.
            channel.close()

            try {
                channel.aAccept()
                fail("aAccept() on a closed channel must not succeed")
            } catch (e: ClosedChannelException) {
                // Correct: passed through unwrapped, so AsyncServerSocket's accept loop recognises
                // it as shutdown and terminates the flow cleanly.
            } catch (e: SocketClosedException) {
                fail(
                    "close-before-accept was wrapped as $e instead of being passed through as " +
                        "ClosedChannelException — the accept loop cannot recognise this as shutdown",
                )
            }
        }
}
