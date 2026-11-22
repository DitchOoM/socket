package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_INVALID_STATE
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.jvm.JvmInline

/**
 * quiche's answer to one ask for a datagram: it had nothing more to send, it wrote a datagram of that many bytes, or
 * it refused with a quiche error code. quiche writes no empty datagram, so zero is nothing more too.
 */
@JvmInline
internal value class DatagramStep(
    val raw: Int,
)

internal inline fun <R> DatagramStep.fold(
    done: () -> R,
    wrote: (length: Int) -> R,
    refused: (code: Int) -> R,
): R =
    when {
        raw > 0 -> wrote(raw)
        raw == 0 || raw == QUICHE_ERR_DONE -> done()
        else -> refused(raw)
    }

/**
 * What a connection whose quiche refused to write a datagram carries as its cause: quiche's error [code]. Carried by
 * the end, never thrown.
 */
internal class QuicheSendRefused(
    val code: Int,
) : Throwable()

/** Runs the end a write that ended as [this] calls for: [mismatch] for another clock, [refused] for quiche's refusal. */
internal inline fun Wrote.ends(
    mismatch: () -> Unit,
    refused: () -> Unit,
) = when (this) {
    Wrote.All, Wrote.BatchFull -> Unit
    Wrote.ClockMismatch -> mismatch()
    Wrote.Refused -> refused()
}

/**
 * Inside: the end of a connection whose quiche refused to write. quiche's own error when it recorded one; no usable path
 * when it had no path to write on; else processing failed with quiche's code. The close goes on the wire where quiche
 * still lets us.
 */
internal fun ConnParts.closeRefused(now: Nanos): ConnEnd {
    val refusal = egress.refusal()
    val otherwise =
        if (refusal.code == QUICHE_ERR_INVALID_STATE) ConnEnd.NoUsablePath else ConnEnd.ProcessingFailed(Thrown(refusal))
    val end = life.refusedEnd(otherwise)
    close(CloseChoice.Immediate(QuicError.ProcessingFailed), end, now)
    return end
}
