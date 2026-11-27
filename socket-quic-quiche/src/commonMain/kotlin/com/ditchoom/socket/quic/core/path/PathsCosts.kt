@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: what a client's moves spend is here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.udp.SocketTicket
import kotlin.random.Random

/**
 * What a client's moves spend, and the asks that spend it: the spare ids handed to the peer and read back retired, the
 * sockets asked of the loop, each named by a ticket minted here so every answer of the loop names its ask, and the
 * endpoint a move leaves, kept until the next one is left and then freed. Inside the connection only.
 */
@Confined
internal class MoveCosts(
    private val q: QuicheConnRef,
    private val ids: IdIssuer,
    private val random: Random,
    factory: BufferFactory,
    private val free: (QuicheRecvInfo, Made) -> Unit,
    private val ask: (SocketAsk) -> Unit,
) {
    private var previous: Previous = Previous.None
    private val retiredIds = RetiredReader(factory)

    /** The ticket of the last socket asked of the loop; the connect's socket is [SocketTicket.FIRST]. */
    private var lastTicket = SocketTicket.FIRST

    /** The connection is established: the peer is given the spare ids a move needs. */
    fun established(): Issued = ids.issueAll(q, random)

    /** Hands the peer the ids the paths of moves to come use, one each, after reading out of quiche those it retired. */
    fun topUp() {
        retiredIds.read(q)
        ids.issueAll(q, random)
    }

    /** The ticket the next socket asked of the loop is named by. */
    fun nextTicket(): SocketTicket = lastTicket.next().also { lastTicket = it }

    /** Asks the loop for a second socket, [ticket], where [wanted] says. */
    fun open(
        ticket: SocketTicket,
        wanted: MoveWanted,
    ) = ask(SocketAsk.OpenBeside(ticket, wanted))

    /** Asks the loop to carry on the second socket [ticket] from now on, closing the old one. */
    fun promote(ticket: SocketTicket) = ask(SocketAsk.Promote(ticket))

    /** Asks the loop to close the second socket [ticket], open or still being made. */
    fun close(ticket: SocketTicket) = ask(SocketAsk.CloseSecond(ticket))

    /** The second endpoint [local] is left: its path's id retired, its socket closed (once the loop's own close: a no-op), spent. */
    fun dropSecond(
        local: LocalAddr,
        id: LinkedId,
    ) {
        q.retire(id)
        close(local.socket)
        spend(local)
    }

    /** Ends [unfinished] ([dropSecond]): answers the endpoint the connection's path still leaves from. */
    fun abandon(unfinished: Move.Unfinished): Linked =
        when (unfinished) {
            is Move.Asked -> unfinished.from.also { close(unfinished.ticket) }
            is Move.Probing -> unfinished.from.also { dropSecond(unfinished.to.local, LinkedId.Seq(unfinished.to.dcidSeq)) }
            is Move.Stranded -> unfinished.from.also { dropSecond(unfinished.at, unfinished.id) }
        }

    /** [local] is left behind: it is kept as the previous endpoint, and the one kept before it is freed. */
    fun spend(local: LocalAddr) {
        previous.held { left -> free(left.recvInfo, left.made) }
        previous = Previous.Held(local)
    }

    /** Hands [each] the endpoint kept, for its release, and frees the ids' own memory. */
    fun release(each: (LocalAddr) -> Unit) {
        previous.held(each)
        ids.free()
        retiredIds.free()
    }
}
