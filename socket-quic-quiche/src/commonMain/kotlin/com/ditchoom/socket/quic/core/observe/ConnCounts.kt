package com.ditchoom.socket.quic.core.observe

/** What a connection did with the threads and datagrams that reached it: any thread counts, tests read. */
internal class ConnCounts {
    private val entered = Counter()
    private val busy = Counter()
    private val handed = Counter()
    private val copied = Counter()
    private val dropped = Counter()
    private val late = Counter()
    private val resent = Counter()

    /** Times a caller entered a free connection and served it on its own thread. */
    val enteredInPlace: Long get() = entered.value()

    /** Times a caller left work for the thread already inside. */
    val leftForHolder: Long get() = busy.value()

    /** Times work reached a free connection and the loop was asked to serve it. */
    val handedToLoop: Long get() = handed.value()

    /** Datagrams that arrived while a caller was inside and were copied into the connection's work. */
    val datagramsCopied: Long get() = copied.value()

    /** Datagrams that arrived after the connection stopped taking work, or found no memory to be copied into. */
    val datagramsDropped: Long get() = dropped.value()

    /** Datagrams that arrived after the connection ended: none is handed to quiche. */
    val lateDatagrams: Long get() = late.value()

    /** Times a lingering connection answered a late datagram with its CONNECTION_CLOSE again. */
    val closesResent: Long get() = resent.value()

    fun enteredInPlace() = entered.increment()

    fun leftForHolder() = busy.increment()

    fun handedToLoop() = handed.increment()

    fun datagramCopied() = copied.increment()

    fun datagramDropped() = dropped.increment()

    fun lateDatagram() = late.increment()

    fun closeResent() = resent.increment()
}
