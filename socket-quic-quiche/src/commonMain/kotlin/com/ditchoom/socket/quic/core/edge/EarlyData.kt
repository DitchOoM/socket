package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.ConnectionEarlyDataScope
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.core.conn.FirstFlight
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.conn.offerSession
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/**
 * Offers [resumption]'s ticket on a client connection quiche created and has not sent on. With early data the first
 * flight is held until the caller's 0-RTT block returns, so its writes leave with the ClientHello.
 */
internal fun offerOf(
    q: QuicheConnRef,
    resumption: QuicResumption,
    factory: BufferFactory,
): Offer =
    when (resumption) {
        QuicResumption.None -> Offer.NoTicket
        is QuicResumption.Resume -> offered(q, resumption.ticket, FirstFlight.Immediate, factory)
        is QuicResumption.ResumeWithEarlyData -> offered(q, resumption.ticket, FirstFlight.HeldForEarlyData, factory)
    }

private fun offered(
    q: QuicheConnRef,
    ticket: QuicSessionTicket,
    flight: FirstFlight,
    factory: BufferFactory,
): Offer {
    val serialized = ticket.serialized()
    val length = serialized.remaining()
    val buffer = factory.allocate(length)
    try {
        buffer.write(serialized)
        return when (val addressed = NativeBytes.of(buffer)) {
            is Addressed.Native -> offerSession(q, addressed.bytes, length, flight)
            is Addressed.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
        }
    } finally {
        buffer.freeNativeMemory()
    }
}

/**
 * Runs the caller's [QuicResumption.ResumeWithEarlyData] block once, where [offer] says it belongs: before the first
 * flight when quiche took the ticket, so its writes go as 0-RTT, and after [awaitEstablished] otherwise, as ordinary
 * data. [awaitEstablished] runs exactly once either way.
 */
internal suspend fun CoreConnection.runEarlyData(
    resumption: QuicResumption,
    offer: Offer,
    awaitEstablished: suspend () -> Unit,
) {
    val block =
        when (resumption) {
            QuicResumption.None, is QuicResumption.Resume -> return awaitEstablished()
            is QuicResumption.ResumeWithEarlyData -> resumption.write
        }
    val scope = ConnectionEarlyDataScope(this)
    when (flightOf(offer)) {
        FirstFlight.HeldForEarlyData -> {
            try {
                scope.block()
            } finally {
                releaseFirstFlight()
            }
            awaitEstablished()
        }
        // quiche refused the ticket, so there is no 0-RTT: the block's bytes are ordinary data.
        FirstFlight.Immediate -> {
            awaitEstablished()
            scope.block()
        }
    }
}

private fun flightOf(offer: Offer): FirstFlight =
    when (offer) {
        is Offer.Offered -> offer.flight
        is Offer.NoTicket, is Offer.Unusable -> FirstFlight.Immediate
    }
