package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined

/** Whether the connection's ACK waits for the next serve of this pass. */
private enum class PassHold { Open, Waiting }

/**
 * The ACK of a turn that handed received data to the application waits for one more serve of the same loop pass: work
 * that turn queued, a reply an application handed in place among it, runs first and the ACK rides with what it wrote.
 * Otherwise that serve writes the ACK alone, in the same pass, so it never waits for a thread or across a turn.
 */
@Confined
internal class SamePassHold {
    private var phase = PassHold.Open
    private var received = 0
    private var seenHanded = 0L
    private var seenOutput = 0L

    /** Waits begun, and waits that ended with something the ACK rode on, over the connection's life. */
    var waits = 0L
        private set
    var rides = 0L
        private set

    /** Quiche read a datagram of this connection. */
    fun received() {
        received++
    }

    /** Before a serve's write, from the monotonic counts of data [handed] up and of [output] handed to quiche. */
    fun decide(
        handed: Long,
        output: Long,
    ): AckStep {
        val newlyHanded = handed != seenHanded
        val replied = output != seenOutput
        seenHanded = handed
        seenOutput = output
        val datagrams = received
        received = 0
        return when (phase) {
            PassHold.Waiting -> {
                phase = PassHold.Open
                if (replied) rides++
                AckStep.Write
            }
            PassHold.Open ->
                if (datagrams > 0 && newlyHanded && !replied) {
                    phase = PassHold.Waiting
                    waits++
                    AckStep.UntilPassEnds
                } else {
                    AckStep.Write
                }
        }
    }
}
