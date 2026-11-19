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

    /**
     * How the longest pass so far split: deadlines and readiness, receiving, handed-over work, serving; and the longest
     * single serve, timed from the end of the last serve that sent to the end of the next one, so idle serves read no clock.
     */
    var longestFire = 0L
        private set
    var longestReceive = 0L
        private set
    var longestWork = 0L
        private set
    var longestServe = 0L
        private set
    var longestSingleServe = 0L
        private set

    fun phased(
        fire: Long,
        receive: Long,
        work: Long,
        serve: Long,
        slowestServe: Long,
    ) {
        if (fire + receive + work + serve <= longestFire + longestReceive + longestWork + longestServe) return
        longestFire = fire
        longestReceive = receive
        longestWork = work
        longestServe = serve
        longestSingleServe = slowestServe
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
