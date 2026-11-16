package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Opened
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

/**
 * Opens the next bidirectional stream. At the peer's stream limit it waits, behind any open already waiting, until
 * the peer grants another stream or the connection ends.
 */
internal class OpenOp(
    private val cont: CancellableContinuation<QuicByteStream>,
    private val conn: Conn,
    private val streamOf: (Long) -> QuicByteStream,
) : ConnWork(),
    Waiter {
    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Open -> Unit
            }
            if (inside.streams.opensWaiting() > 0 || retry(inside.streams) == Retry.Again) inside.streams.waitToOpen(this)
        }

    override fun retry(table: StreamTable): Retry {
        if (!cont.isActive) return Retry.Settled
        return when (table.tryOpen()) {
            Opened.AtLimit -> Retry.Again
            Opened.Opened -> {
                cont.resume(streamOf(table.lastOpened().id))
                Retry.Settled
            }
        }
    }

    override fun connectionEnded(end: ConnEnd) = cont.fail(Raised.ConnEnded(end), QuicCloseCircumstance.Ended)

    override fun gone(end: ConnEnd) = connectionEnded(end)

    /** The caller was cancelled while the open waited for the peer's credit. */
    fun withdraw() = conn.submit(Withdraw(), CallerPlace.HandToLoop)

    private fun failed(failure: Exception) = cont.fail(Raised.Thrown(failure), QuicCloseCircumstance.Ended)

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) = inside.streams.withdrawOpen(this@OpenOp)

        override fun gone(end: ConnEnd) = Unit
    }
}
