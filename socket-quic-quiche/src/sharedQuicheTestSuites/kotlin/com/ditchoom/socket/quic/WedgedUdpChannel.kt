package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * A datapath that is **wedged**, not dead: every send suspends and never completes.
 *
 * This is deliberately a different double from [StubUdpChannel]'s throwing `sendBehavior`, and the
 * difference is the point. A send that *throws* returns control to the driver loop, which is what
 * lets the loop get back to its `select`, arm the next timer, and dequeue the next command. A send
 * that *parks* never returns at all, so the loop stops being a loop — every timer it is responsible
 * for arming and every command it is responsible for draining stops with it.
 *
 * It is what a real platform does when the datapath stops answering rather than erroring. On Apple
 * the client's datagrams ride an `NWConnection` whose completion handler runs on a serial queue, and
 * a handler that is never invoked is indistinguishable, from inside `send`, from one that has merely
 * not been invoked *yet* — there is no errno to classify and nothing to time out against.
 *
 * [gate] exists only so a test can unwind the driver after asserting; nothing in production
 * completes it, which is precisely the condition under test. Complete it in a `finally` or the
 * driver holds the test scope open and the real assertion is reported as a leaked coroutine.
 */
class WedgedUdpChannel(
    private val gate: CompletableDeferred<Unit> = CompletableDeferred(),
) : UdpChannel {
    var sendCount: Int = 0
        private set

    override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ): SendOutcome {
        sendCount++
        gate.await()
        return SendOutcome.Sent
    }

    /** Let the parked send return, so a finished test can unwind the driver loop. */
    fun release() {
        if (!gate.isCompleted) gate.complete(Unit)
    }

    /**
     * How many times the driver closed this channel. The stall branch closes the socket the moment
     * the bound fires — that is what ends the platform operation still holding the driver's reusable
     * send buffer — so this is the most direct observation of the bound firing there is.
     */
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
    }
}
