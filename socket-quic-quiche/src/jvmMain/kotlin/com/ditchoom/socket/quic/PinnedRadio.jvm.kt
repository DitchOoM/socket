package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.Job
import kotlin.time.Duration

/** A desktop JVM cannot bind a socket to one radio. */
internal actual val platformPinnableRadios: Set<NetworkKind.Radio> = emptySet()

internal actual suspend fun pinnedRadioOpener(
    radio: NetworkKind.Radio,
    bound: Duration,
    lifetime: Job,
): ConnectedUdpOpener = throw PinnedRadioRefusedException(radio, PinnedRadioRefusal.EngineCannotPin)
