package com.ditchoom.socket.quic.core.observe

/** What one loop did, counted on its thread; read on that thread or after the loop stopped. */
@LoopOnly
internal class LoopCounts {
    var passes = 0L
        private set
    var waits = 0L
        private set
    var datagramsIn = 0L
        private set
    var datagramsOut = 0L
        private set
    var sendRefusals = 0L
        private set
    var receiveFailures = 0L
        private set
    var work = 0L
        private set

    /** Nanoseconds spent in passes, in waits for input, and in the longest single pass. */
    var passNanos = 0L
        private set
    var waitNanos = 0L
        private set
    var longestPassNanos = 0L
        private set

    fun timedPass(nanos: Long) {
        passNanos += nanos
        if (nanos > longestPassNanos) longestPassNanos = nanos
    }

    fun timedWait(nanos: Long) {
        waitNanos += nanos
    }

    fun passed() {
        passes++
    }

    fun waited() {
        waits++
    }

    fun received(count: Int) {
        datagramsIn += count
    }

    fun sent(count: Int) {
        datagramsOut += count
    }

    fun refused() {
        sendRefusals++
    }

    fun receiveFailed() {
        receiveFailures++
    }

    /** Times the loop cut a socket from lanes that stalled past their bound. */
    var lanesCut = 0L
        private set

    fun cutLanes() {
        lanesCut++
    }

    fun ran(count: Int) {
        work += count
    }
}
