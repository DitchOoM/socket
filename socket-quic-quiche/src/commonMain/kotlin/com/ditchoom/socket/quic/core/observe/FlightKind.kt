package com.ditchoom.socket.quic.core.observe

/**
 * What one flight-recorder record says. The fields a record carries by kind (`sub` is a small code the caller owns):
 *
 * | kind | local, peer | length | sub | a | b |
 * |---|---|---|---|---|---|
 * | [DatagramIn], [DatagramOut] | address ids | datagram bytes | 0 | where its payload starts in the arena | 0 |
 * | [TimerArm] | 0 | 0 | 0 | due time | 0 |
 * | [TimerFire] | 0 | 0 | 0 | the time it was due | 0 |
 * | [OpSubmit], [OpComplete] | 0 | 0 | operation kind | stream id | bytes |
 * | [NetworkEvent] | address ids | 0 | event code | detail | detail |
 * | [QuichePathEvent] | the path's address ids | 0 | quiche's event type | detail | detail |
 * | [ConnEnd] | 0 | 0 | the end's kind | error code | 0 |
 */
internal enum class FlightKind {
    DatagramIn,
    DatagramOut,
    TimerArm,
    TimerFire,
    OpSubmit,
    OpComplete,
    NetworkEvent,
    QuichePathEvent,
    ConnEnd,
}
