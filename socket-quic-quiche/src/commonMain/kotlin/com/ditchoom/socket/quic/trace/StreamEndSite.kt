package com.ditchoom.socket.quic.trace

/**
 * Where a stream's read side latched its terminal verdict — which of the three places in
 * `QuicheDriver` set `StreamSlot.end` away from `Open`.
 *
 * Sealed rather than a string, per this repo's rule that a reason stays typed to its last boundary;
 * `QuicTraceRecorder.streamEnd` is the one place it becomes a frozen v1 wire token.
 *
 * The site is the whole diagnostic value of the event. A latch is irreversible and answers every
 * subsequent `streamRead` from the slot, so an early one ends a stream the connection then keeps alive
 * — issue #393's device signature. All three sites are reachable on a healthy stream, but they are not
 * equally suspicious, and a trace that says only "the stream ended" cannot tell them apart.
 */
sealed interface StreamEndSite {
    /**
     * `drainStreamIntoSlot` — the teardown-edge drain (issue #318). Expected: the connection is going
     * away and quiche's remaining bytes are being moved into the slot before it does.
     */
    data object TeardownDrain : StreamEndSite

    /**
     * `salvageCancelledRecv` — a `StreamRecv` answered into a read that had already unwound (issue
     * #393's window). **The suspect.** The salvage rescues the chunk's bytes *and* its FIN, and it
     * latches on the branch where the chunk queued successfully — so no byte is lost and
     * `StreamLoss` records nothing, while the stream is nonetheless finished for good. If a trace
     * shows a stream dying with this site on the latch and traffic afterwards, that is the defect,
     * not a coincidence.
     */
    data object CancelledRecvSalvage : StreamEndSite

    /**
     * The `StreamRecv` result delivered to a live `streamRead`. The ordinary path: the peer really did
     * finish (or reset) the stream and a reader was there to see it.
     */
    data object ReadDelivery : StreamEndSite
}
