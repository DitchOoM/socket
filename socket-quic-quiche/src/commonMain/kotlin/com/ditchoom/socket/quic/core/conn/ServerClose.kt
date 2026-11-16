@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where the close a server asked of one of its connections stands. It only moves forward. */
internal enum class ServerCloseStage {
    /** The server asked for no close. */
    Open,

    /** The close is queued for whoever is inside. */
    Asked,

    /** The close ran inside; its CONNECTION_CLOSE is with the thread inside, not yet handed to the socket. */
    Ran,

    /** Whoever ran the close collected its output, handed it to the socket and left. */
    Out,
}

/** Whether a call moved the stage. */
internal enum class Moved { Yes, No }

/**
 * A connection's [ServerCloseStage]. The loop asks, the thread inside marks the close as run, and the first thread to
 * leave after collecting the connection's output marks it out. Each step is one compare-and-set from the stage before,
 * so it happens once, and since the stage never goes back, a stage seen once is never seen again.
 */
@Published
internal class ServerCloseCell {
    private val stage = AtomicInt(OPEN)

    fun stage(): ServerCloseStage = ServerCloseStage.entries[stage.load()]

    /** Loop thread. */
    fun ask() {
        stage.compareAndSet(OPEN, ASKED)
    }

    /** Inside. */
    fun ran() {
        stage.compareAndSet(ASKED, RAN)
    }

    /** Whoever leaves after collecting the connection's output. */
    fun out(): Moved = if (stage.compareAndSet(RAN, OUT)) Moved.Yes else Moved.No
}

/** Inside, once: the server closes the connection with NO_ERROR. */
internal class ServerCloseWork(
    private val cell: ServerCloseCell,
) : ConnWork() {
    override fun run(inside: Inside) {
        inside.close(QuicError.NoError)
        cell.ran()
    }

    override fun gone(end: ConnEnd) = Unit
}

private const val OPEN = 0
private const val ASKED = 1
private const val RAN = 2
private const val OUT = 3
