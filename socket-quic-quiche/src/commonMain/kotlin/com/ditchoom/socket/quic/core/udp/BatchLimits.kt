package com.ditchoom.socket.quic.core.udp

/** How many datagrams a batch holds and how many bytes each slot may carry. */
internal class BatchLimits(
    val maxDatagrams: Int,
    val maxDatagramSize: Int,
)

/** Whether a [SendBatch] can take another datagram. */
internal enum class Room { Open, Full }
