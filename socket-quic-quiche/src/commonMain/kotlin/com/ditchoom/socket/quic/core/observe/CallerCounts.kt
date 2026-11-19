package com.ditchoom.socket.quic.core.observe

/** What threads other than the loop did to a loop: any thread counts, tests read. */
internal class CallerCounts {
    private val sends = Counter()
    private val datagrams = Counter()
    private val deferred = Counter()
    private val wakes = Counter()
    private val cuts = Counter()

    /** Sends a thread other than the loop made. */
    val sendsInPlace: Long get() = sends.value()

    /** Datagrams the network took from those sends. */
    val datagramsInPlace: Long get() = datagrams.value()

    /** Sends the network refused part of, which the loop finished. */
    val sendsDeferred: Long get() = deferred.value()

    /** Times a thread other than the loop ended the loop's wait. */
    val loopWakes: Long get() = wakes.value()

    fun sent(count: Int) {
        sends.increment()
        datagrams.add(count)
    }

    fun deferred() = deferred.increment()

    fun woke() = wakes.increment()

    /** Sends a lane tried after its socket was cut from its lanes; none reached the socket. */
    val sendsCut: Long get() = cuts.value()

    fun cut() = cuts.increment()
}
