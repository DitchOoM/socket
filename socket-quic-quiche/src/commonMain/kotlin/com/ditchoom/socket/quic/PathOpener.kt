package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkKind

/**
 * How every socket of a client connection under this policy is opened: through [pinned] for the radio
 * of a [MigrationPolicy.Pinned] connection, and through the platform default otherwise.
 */
internal suspend fun MigrationPolicy.pathOpener(pinned: suspend (NetworkKind.Radio) -> ConnectedUdpOpener): ConnectedUdpOpener =
    when (this) {
        is MigrationPolicy.Pinned -> pinned(radio)
        MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic -> ConnectedUdpOpener.Platform
    }
