@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.ProbeRefusal
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.core.conn.AccessGuard
import com.ditchoom.socket.quic.core.conn.AccessVerdict
import com.ditchoom.socket.quic.core.conn.judge
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.core.udp.connectRefusalOf
import com.ditchoom.socket.quic.refusal

/** A route probe the system refused as it refuses an app the local network is that denial; else the route is unresolved. */
internal fun unresolved(
    reason: RouteProbeFailure,
    peer: PathKey,
    access: AccessGuard,
): Raised =
    when (reason) {
        is RouteProbeFailure.ProbeRefused ->
            when (val refused = reason.refusal()) {
                is ProbeRefusal.Connect ->
                    when (val verdict = access.judge(connectRefusalOf(refused.error, Thrown(refused.thrown)), peer)) {
                        is AccessVerdict.Denied -> Raised.AccessDenied(verdict.denial)
                        is AccessVerdict.NotDenied -> Raised.RouteUnresolved(reason)
                    }
                is ProbeRefusal.Other -> Raised.RouteUnresolved(reason)
            }
        is RouteProbeFailure.SourceAddressUnknown, is RouteProbeFailure.SourceAddressUnnamed -> Raised.RouteUnresolved(reason)
    }
