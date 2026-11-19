package com.ditchoom.socket.quic.core.observe

/**
 * What one flight-recorder record says. The fields a record carries by kind (`sub` is a small code the caller owns):
 *
 * | kind | local, peer | length | sub | a | b |
 * |---|---|---|---|---|---|
 * | [DatagramIn], [DatagramOut] | address ids | datagram bytes | 0 | where its payload starts in the arena | 0 |
 * | [TimerArm] | 0 | 0 | 0 | due time | 0 |
 * | [TimerFire] | 0, the peer's address id | 0 | 0 | 0 | 0 |
 * | [OpSubmit], [OpComplete] | 0 | 0 | operation kind | stream id | bytes |
 * | [NetworkEvent] | address ids | 0 | event code | detail | detail |
 * | [QuichePathEvent] | the path's address ids | 0 | quiche's event type | detail | detail |
 * | [ConnEnd] | 0 | 0 | the end's kind | error code | 0 |
 * | [DatagramDropped] | the address it was for or from, the peer's | datagram bytes | why it was dropped | 0 | 0 |
 * | [PacingClockMismatch] | 0, the peer's address id | 0 | 0 | how far ahead of the loop's clock the due time was, in ns | 0 |
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
    DatagramDropped,
    PacingClockMismatch,
}
