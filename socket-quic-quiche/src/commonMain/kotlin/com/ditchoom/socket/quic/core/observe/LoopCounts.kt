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

    fun ran(count: Int) {
        work += count
    }
}
