package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** MEASUREMENT ONLY (not for merge): when the server's reader lets the receive loop run, and what each did. */
internal sealed interface ReaderYield {
    /** After every [datagrams] read in a row. */
    class Every(
        val datagrams: Int,
    ) : ReaderYield

    /** Never: read until the socket is empty. */
    data object Never : ReaderYield

    /** After a datagram that found nothing held, which is the one that wakes a waiting loop. */
    data object WhenItWakesTheLoop : ReaderYield
}

@OptIn(ExperimentalAtomicApi::class)
internal object IntakeExperiment {
    var readerYield: ReaderYield = ReaderYield.Every(ServerDatagramIntake.DATAGRAMS_PER_READ)
    var capacity: Int = ServerDatagramIntake.HELD_DATAGRAMS

    /** Datagrams the loop may take without waiting before it waits again; 0 is the loop as it was. */
    var loopTakesPerPass: Int = ServerDatagramIntake.DATAGRAMS_PER_PASS

    /** Bytes of kernel receive buffer the server asks for; 0 leaves the socket as the host made it. */
    var kernelReceiveBufferBytes: Int = SERVER_KERNEL_RECEIVE_BUFFER_BYTES

    val datagramsRead = AtomicLong(0)
    val readerYields = AtomicLong(0)
    val loopWaits = AtomicLong(0)
    val takenByWaiting = AtomicLong(0)
    val takenWithoutWaiting = AtomicLong(0)
    val loopYields = AtomicLong(0)

    fun counters(): String =
        "datagrams_read=${datagramsRead.load()} reader_yields=${readerYields.load()} loop_waits=${loopWaits.load()} " +
            "taken_by_waiting=${takenByWaiting.load()} taken_without_waiting=${takenWithoutWaiting.load()} loop_yields=${loopYields.load()}"
}
