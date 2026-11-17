package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.KeptTicket
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.RunningLoop
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

/** The quiche handle a connect made, kept with what it needs to be built on the loop. */
internal class ClientWiring(
    val api: QuicheApi,
    val handle: QuicheConn,
    val config: QuicheConfig,
    val options: QuicOptions,
    val memory: EdgeMemory,
    val running: RunningLoop,
    val socket: StackSocket,
    val wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
    val peer: PathKey,
    val readPolicy: ReadPolicy,
    val writePolicy: WritePolicy,
    val scope: CoroutineScope,
    val flight: FlightSize,
    val handshake: HandshakeRule,
    /** What the connection reads the time from: the loop's own clock. */
    val clock: LoopClock = MonotonicClock,
    /**
     * When the loop reads the socket: polled on a loop thread; every pass for a socket that reports by completion, or on a
     * loop a test drives on virtual time.
     */
    val readiness: Readiness = Readiness.Polled,
    /** Where the ids the connection hands its peer for a new path are drawn from. */
    val ids: Random = connectionIdRandom,
    /** The session ticket offered before the first flight, and whether that flight waits for 0-RTT. */
    val offer: Offer = Offer.NoTicket,
    /** Where the ticket the server issues is kept for the next connection to it. */
    val tickets: TicketHome = TicketHome.NotKept,
    /** The ticket [offer] took from that store, which goes back if the connection never sends. */
    val kept: KeptTicket = KeptTicket.None,
)
