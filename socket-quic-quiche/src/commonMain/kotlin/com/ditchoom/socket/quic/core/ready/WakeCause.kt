package com.ditchoom.socket.quic.core.ready

/** Why a [Poller.await] returned. */
internal enum class WakeCause { Readable, Writable, Woken, Deadline }

/** Whether the loop waits for a socket to take more output: only while the network refused a send. */
internal enum class WriteInterest { None, Wanted }
