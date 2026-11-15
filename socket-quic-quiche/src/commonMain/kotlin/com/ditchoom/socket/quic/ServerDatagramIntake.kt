package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.DatagramReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.yield

/**
 * Takes datagrams off a server's socket as fast as the socket gives them and holds up to [capacity] for the
 * receive loop.
 *
 * The kernel's receive buffer for a UDP socket holds a few hundred small datagrams by default, and a burst
 * from many connections arrives faster than the loop routes one datagram: what does not fit, the kernel drops.
 * Held here a burst waits its turn. When [capacity] datagrams are held the reader stops reading, and the
 * kernel's buffer is the bound again.
 */
internal class ServerDatagramIntake(
    private val receive: suspend () -> DatagramReadResult,
    private val isClosing: () -> Boolean,
    capacity: Int = HELD_DATAGRAMS,
) {
    // A datagram the reader queued and the loop never took is freed by the channel, never by the reader, whose
    // cancelled `send` cannot say whether the hand-off happened.
    private val held = Channel<DatagramReadResult>(capacity, onUndeliveredElement = ::freeUndeliveredDatagram)

    /** The next held datagram, for the loop's wait; a closed result means the reader has ended. */
    val onNext: SelectClause1<ChannelResult<DatagramReadResult>> get() = held.onReceiveCatching

    /** The next held datagram without waiting. */
    fun poll(): Held {
        val next = held.tryReceive()
        return when {
            next.isSuccess -> Held.Next(next.getOrThrow())
            next.isClosed -> Held.ReaderEnded
            else -> Held.Nothing
        }
    }

    /**
     * The reader: receives until the socket closes or its coroutine is cancelled. A receive that throws is
     * retried, unless the server is closing.
     *
     * After [DATAGRAMS_PER_READ] datagrams in a row it yields, so the receive loop, which may share its thread,
     * routes them before more are read: a reader that read until the socket was empty held every datagram back
     * for as long as others kept arriving.
     */
    suspend fun read() {
        var readInARow = 0
        try {
            while (true) {
                val result =
                    try {
                        receive()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (isClosing()) break else continue
                    }
                held.send(result)
                if (result is DatagramReadResult.Closed) break
                if (++readInARow == DATAGRAMS_PER_READ) {
                    readInARow = 0
                    yield()
                }
            }
        } catch (_: CancellationException) {
        } finally {
            held.close()
        }
    }

    /** Frees what is held and whatever the reader is still handing over: the loop has stopped taking. */
    fun freeHeld() {
        held.cancel()
    }

    /** What [poll] found. */
    sealed interface Held {
        class Next(
            val result: DatagramReadResult,
        ) : Held

        /** Nothing is held now; the reader is still reading. */
        data object Nothing : Held

        /** Nothing is held and the reader has ended. */
        data object ReaderEnded : Held
    }

    companion object {
        /** Two datagrams (data and acknowledgement) from each of 2,048 connections arriving at once. */
        const val HELD_DATAGRAMS = 4096

        /** Datagrams the reader takes in a row before it lets the receive loop route them. */
        const val DATAGRAMS_PER_READ = 32

        /** Datagrams the receive loop routes in a row before it lets other work on its thread run. */
        const val DATAGRAMS_PER_PASS = 256
    }
}
