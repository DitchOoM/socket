@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.ProbeRefusal
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.core.conn.AccessGuard
import com.ditchoom.socket.quic.core.conn.AccessVerdict
import com.ditchoom.socket.quic.core.conn.ProbeTurn
import com.ditchoom.socket.quic.core.conn.judgeProbe
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.core.udp.connectRefusalOf
import com.ditchoom.socket.quic.refusal

/**
 * A route probe to [peer] that the system refused as it refuses an app the local network is that denial; else the route
 * is unresolved. [turn] says whether it was the first probe to that peer in this process.
 */
internal fun unresolved(
    reason: RouteProbeFailure,
    peer: PathKey,
    access: AccessGuard,
    turn: ProbeTurn,
): Raised =
    when (reason) {
        is RouteProbeFailure.ProbeRefused ->
            when (val refused = reason.refusal()) {
                is ProbeRefusal.Connect ->
                    when (val verdict = access.judgeProbe(connectRefusalOf(refused.error, Thrown(refused.thrown)), peer, turn)) {
                        is AccessVerdict.Denied -> Raised.AccessDenied(verdict.denial)
                        is AccessVerdict.NotDenied -> Raised.RouteUnresolved(reason)
                    }
                is ProbeRefusal.Other -> Raised.RouteUnresolved(reason)
            }
        is RouteProbeFailure.SourceAddressUnknown, is RouteProbeFailure.SourceAddressUnnamed -> Raised.RouteUnresolved(reason)
    }
