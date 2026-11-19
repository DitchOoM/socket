package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.core.life.ConnLife
import com.ditchoom.socket.quic.core.sched.SendLedger
import com.ditchoom.socket.quic.core.sched.WorkInbox
import com.ditchoom.socket.quic.core.stream.StreamTable

/** The components of one connection, each held here and nowhere else. An immutable record; [Turn] orders them. */
internal class ConnParts(
    val role: QuicRole,
    val life: ConnLife,
    val handshake: Handshake,
    val streams: StreamTable,
    val timer: ConnTimer,
    val ingress: Ingress,
    val egress: Egress,
    val release: Release,
    val network: NetworkWatch,
    val flight: ConnFlight,
    val inbox: WorkInbox<ConnWork>,
    val slots: ConnSlots,
    val sink: ConnSink,
    val ackReads: AckHoldReads,
    val sends: SendLedger,
    val endHold: EndHold,
)
