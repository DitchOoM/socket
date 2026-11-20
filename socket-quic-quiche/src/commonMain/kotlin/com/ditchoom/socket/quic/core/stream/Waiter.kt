package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.core.life.ConnEnd

/** What a parked operation says when the table retries it. */
internal enum class Retry {
    /** It finished: resumed its caller, or found its caller gone. */
    Settled,

    /** Still waiting. */
    Again,
}

/** A parked operation the [StreamTable] resumes. Runs inside the connection. */
internal interface Waiter {
    fun retry(table: StreamTable): Retry

    /** The connection ended with the operation still parked. */
    fun connectionEnded(end: ConnEnd)

    /** Whether a read's caller still has to take bytes it was handed; nothing else ever holds any. */
    fun inHand(): InHand = InHand.No
}

/** Whether a read's caller still has to take bytes it was handed. */
internal enum class InHand { Yes, No }

/** The slot of a stream that nothing waits on. */
internal object Nobody : Waiter {
    override fun retry(table: StreamTable): Retry = Retry.Settled

    override fun connectionEnded(end: ConnEnd) = Unit
}
