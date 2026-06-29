package com.ditchoom.socket.quic

/** Maps the public sealed [CongestionControl] to quiche's C enum value (quiche backend only). */
internal val CongestionControl.quicheValue: Int
    get() =
        when (this) {
            is CongestionControl.Reno -> 0
            is CongestionControl.Cubic -> 1
            is CongestionControl.Bbr2 -> 4
        }
