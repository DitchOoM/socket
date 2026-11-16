package com.ditchoom.socket.quic.core.udp

/** How many datagrams a batch holds and how many bytes each slot may carry. */
internal class BatchLimits(
    val maxDatagrams: Int,
    val maxDatagramSize: Int,
)

/** Whether a [SendBatch] can take another datagram. */
internal enum class Room { Open, Full }

/** The reply addresses a JDK socket keeps converted per lane; past it a server converts again. */
internal const val MAX_CACHED_TARGETS = 4096
