package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.NameResolution
import com.ditchoom.socket.candidatesFor

/**
 * The address literal a connection's peer is keyed by: [host] resolved through the platform's resolver, which
 * never blocks the caller's thread and which the caller's cancellation abandons at once.
 */
internal suspend fun peerHostFor(host: String): String =
    NameResolution.Platform
        .candidatesFor(host)
        .first()
        .ip
