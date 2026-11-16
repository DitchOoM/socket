package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.Confined

/**
 * The datagrams a pass has for the network, in order, in one contiguous block: slot `i` starts at
 * `i * maxDatagramSize`. Datagrams the network did not take stay for the next pass; a full batch stops the pass
 * from collecting more output, which is the back-pressure into quiche. One thread uses a batch at a time: the
 * loop, or the caller that holds the lane.
 */
@Confined
internal class SendBatch(
    val storage: NativeBytes,
    val limits: BatchLimits,
    /** Which lane this batch is: 0 is the loop's, a caller's lane is its pool index plus one. */
    val lane: Int = 0,
) {
    private val lengths = IntArray(limits.maxDatagrams)
    private val targets = Array<SendTarget>(limits.maxDatagrams) { SendTarget.ConnectedPeer }
    private var committed = 0
    private var taken = 0

    val pending: Int get() = committed - taken

    fun room(): Room = if (committed == limits.maxDatagrams) Room.Full else Room.Open

    /** Where the next datagram is written, and how much it may hold. */
    fun nextOffset(): Int = committed * limits.maxDatagramSize

    fun commit(
        length: Int,
        target: SendTarget,
    ) {
        lengths[committed] = length
        targets[committed] = target
        committed++
    }

    /** The [n]th datagram not yet taken. */
    fun offsetOf(n: Int): Int = (taken + n) * limits.maxDatagramSize

    fun lengthOf(n: Int): Int = lengths[taken + n]

    fun targetOf(n: Int): SendTarget = targets[taken + n]

    /** The network took the first [count] pending datagrams. */
    fun took(count: Int) {
        taken += count
        if (taken == committed) {
            taken = 0
            committed = 0
        }
    }
}
