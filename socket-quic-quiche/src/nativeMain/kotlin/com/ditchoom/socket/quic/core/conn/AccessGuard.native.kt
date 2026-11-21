package com.ditchoom.socket.quic.core.conn

/** Apple clients ride Network.framework, which names a denial before the connection exists; Linux denies none. */
internal actual val accessGuardOfThisPlatform: AccessGuard = AccessGuard.Unguarded
