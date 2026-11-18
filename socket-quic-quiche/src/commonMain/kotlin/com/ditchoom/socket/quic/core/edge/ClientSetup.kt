package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.core.conn.freeUnbuilt
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.Loop

/**
 * How far a connect's setup got, and so who ends what it made if it fails or is cancelled: each thing it made has one
 * releaser at a time. Until the connection starts that is the connect, itself and then through the connection's
 * release; once it starts, the connection, whose release on its loop frees everything. Each step holds the one before
 * it.
 */
internal sealed interface ClientSetup {
    /** The connect frees [memory]. */
    class Unstarted(
        val memory: EdgeMemory,
    ) : ClientSetup

    /** The connect also gives back the seat [loop] counted it for. */
    class Seated(
        val memory: EdgeMemory,
        val loop: Loop,
    ) : ClientSetup

    /** The connect also closes its socket, which no io has reached a loop with. */
    class Opened(
        val seated: Seated,
        val socket: StackSocket,
    ) : ClientSetup

    /** The connect also frees the peer's address, encoded for quiche. */
    class PeerEncoded(
        val opened: Opened,
        val peer: EncodedSockAddr,
    ) : ClientSetup

    /** The connect also frees its own address, encoded for quiche. */
    class Encoded(
        val opened: Opened,
        val peer: EncodedSockAddr,
        val local: EncodedSockAddr,
    ) : ClientSetup

    /** The connect also frees quiche's connection, which no connection of the core holds yet. */
    class Connected(
        val encoded: Encoded,
        val quiche: QuicheConnRef,
    ) : ClientSetup

    /** The connection holds all of it, unstarted: the connect releases it there, which also closes its socket. */
    class Built(
        val parts: ClientParts,
    ) : ClientSetup

    /** The connect only ends the connection; the connection frees the memory once its loop released it. */
    class Started(
        val started: StartedClient,
    ) : ClientSetup

    /** The connect returned the connection to its caller. */
    data object Established : ClientSetup
}

/** Releases what a connect that failed or was cancelled at this step made, each thing once: what the step added first. */
internal fun ClientSetup.endFailed() {
    when (this) {
        is ClientSetup.Unstarted -> memory.freeAll()
        is ClientSetup.Seated -> {
            loop.seatGivenBack()
            memory.freeAll()
        }
        is ClientSetup.Opened -> {
            socket.close()
            seated.endFailed()
        }
        is ClientSetup.PeerEncoded -> {
            peer.free()
            opened.endFailed()
        }
        is ClientSetup.Encoded -> {
            peer.free()
            local.free()
            opened.endFailed()
        }
        is ClientSetup.Connected -> {
            quiche.freeUnbuilt()
            encoded.endFailed()
        }
        is ClientSetup.Built -> parts.releaseUnstarted()
        is ClientSetup.Started -> started.abandon()
        is ClientSetup.Established -> Unit
    }
}
