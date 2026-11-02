package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.Job
import kotlin.time.Duration

/** The radios [QuicheEngine] can pin a connection to here: both on Android, none on a desktop JVM. */
internal expect val platformPinnableRadios: Set<NetworkKind.Radio>

/**
 * The opener every socket of a connection pinned to [radio] is opened through, waiting at most [bound]
 * for the platform to produce the radio's network and holding it until [lifetime] completes.
 */
internal expect suspend fun pinnedRadioOpener(
    radio: NetworkKind.Radio,
    bound: Duration,
    lifetime: Job,
): ConnectedUdpOpener
