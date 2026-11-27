package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.KeptTicket
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.conn.SendsAccess
import com.ditchoom.socket.quic.core.conn.TokenHome
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.RunningLoop
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.traceRecorderFor
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

/** The quiche handle a connect made, kept with what it needs to be built on the loop. */
internal class ClientWiring(
    val api: QuicheApi,
    val handle: QuicheConn,
    /** The connection's session: its first source id, which names its qlog and key log. */
    val session: QuicSessionId,
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
    /** What a handshake whose sends were all refused says about the app's access, settled on the setup lane. */
    val access: SendsAccess,
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
    /** What [offer] took from the stores (ticket, token), which goes back if the connection never sends. */
    val kept: KeptTicket = KeptTicket.None,
    /** Where the NEW_TOKEN token the server issues is kept for the next connection to it. */
    val tokens: TokenHome = TokenHome.NotKept,
    /** What the connection does with a send time on a clock the loop does not read. */
    val mismatch: MismatchResponse = MismatchResponse.Unpaced,
    /** The connection's trace: the connect makes it once, to judge its options before any socket opens. */
    val capture: TraceCapture = traceRecorderFor(options),
    /** Whether the connection judges its path's silence ([silenceOf]). */
    val silence: Silence,
    /** The network the monitor named as the connect opened its socket; none named on a monitor that names none. */
    val openedOn: NetworkId = NetworkId.Unidentified,
)
