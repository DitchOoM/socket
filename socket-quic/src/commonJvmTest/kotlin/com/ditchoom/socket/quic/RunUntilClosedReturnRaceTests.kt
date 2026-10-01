package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [runUntilClosed] on a multi-threaded dispatcher: the block's last read on a closed connection hands it
 * the stream's end, which leaves nothing unread, and the runner's watcher — on another thread — cancels
 * the block while it is returning what it read. The block has no suspension point left, so it returns its
 * value regardless; the cancellation only reaches the coroutine running it. That value is what the block
 * returned, and the runner must hand it back rather than the connection's close.
 *
 * The Android emulator lost a reply this way: 34816 of 34816 chars read, then
 * `QuicCloseException: connection closed while its block was running`.
 */
class RunUntilClosedReturnRaceTests {
    @Test
    fun aBlockThatReturnsAsTheConnectionsLingerEndsStillReturnsItsValue() =
        runBlocking(Dispatchers.Default) {
            val connection = MockQuicConnection()
            connection.closeByPeerHolding(QuicUnreadAtClose.Unread(setOf(QuicStreamId(0))))
            val returned =
                withTimeout(WITHIN) {
                    connection.runUntilClosed(linger = 1.hours) {
                        // A block suspends before its last read (the runner starts it undispatched, so the
                        // watcher exists only once it has).
                        yield()
                        // The last read returned the stream's end: nothing is left unread.
                        connection.publishUnread(QuicUnreadAtClose.AllRead)
                        // Without suspending, until the watcher on another thread has cancelled this block:
                        // the interleaving a multi-threaded dispatcher allows between that read and the return.
                        val job = currentCoroutineContext().job
                        val started = TimeSource.Monotonic.markNow()
                        while (!job.isCancelled) {
                            check(started.elapsedNow() < WITHIN) { "the watcher never cancelled the block" }
                        }
                        REPLY
                    }
                }
            assertEquals(REPLY, returned, "the block returned its reply; the connection's close must not replace it")
        }

    private companion object {
        const val REPLY = "the whole reply"
        val WITHIN = 10.seconds
    }
}
