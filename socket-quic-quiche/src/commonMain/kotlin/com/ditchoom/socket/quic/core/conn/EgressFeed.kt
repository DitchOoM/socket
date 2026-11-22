package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.time.Nanos

/** Whether quiche's output ran dry: no, yes, or a datagram fed to quiche met a refusal that ends the connection. */
internal enum class Dry { Yes, No, Refused }

/** A write's feed of datagrams queued to send into quiche, each time quiche's output runs dry. */
@Confined
internal class EgressFeed {
    /** The code of quiche's last refusal of a datagram, which ended a write as [Wrote.DatagramRefused]. */
    var refusedCode = 0
        private set

    /**
     * Whether quiche's output ran dry with [written]: it did when quiche wrote nothing, unless it had nothing more to
     * write and [datagrams] queued to send moved some into quiche, which may now write them.
     */
    fun dry(
        written: Int,
        datagrams: Datagrams,
        at: Nanos,
    ): Dry =
        when {
            written > 0 -> Dry.No.also { datagrams.quicheWrote() }
            written == QUICHE_ERR_DONE -> fed(datagrams, at)
            else -> Dry.Yes
        }

    /** Feeds [datagrams] queued to send into quiche: [Dry.No] when some went, so quiche may have more to write. */
    fun fed(
        datagrams: Datagrams,
        at: Nanos,
    ): Dry =
        when (val refill = datagrams.refill(at)) {
            is Refill.Moved -> Dry.No
            is Refill.Nothing -> Dry.Yes
            is Refill.Refused -> {
                refusedCode = refill.code
                Dry.Refused
            }
        }
}
