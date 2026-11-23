package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos

/** Whether quiche's output ran dry: no, yes, or a datagram fed to quiche met a refusal that ends the connection. */
internal enum class Dry { Yes, No, Refused }

/** A write's feed of datagrams queued to send into quiche: before it writes, and each time quiche's output runs dry. */
@Confined
internal class EgressFeed {
    /** The code of quiche's last refusal of a datagram, which ended a write as [Wrote.DatagramRefused]. */
    var refusedCode = 0
        private set

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
