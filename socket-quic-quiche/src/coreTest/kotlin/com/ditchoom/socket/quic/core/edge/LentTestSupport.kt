@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.core.udp.IoDrops
import com.ditchoom.socket.quic.core.udp.LentDrop
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.serveQuicOnCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

internal const val LENT_LOOPBACK = "127.0.0.1"
internal val LENT_WAIT = 10.seconds

/** A lent client on [channel], connected to [port] on loopback, on [loops]. */
internal suspend fun lentClient(
    channel: AddressedDatagramChannel,
    port: Int,
    loops: LoopSet = LoopSet.shared,
    host: String = LENT_LOOPBACK,
): CoreClient =
    coreConnect(
        host = host,
        port = port,
        serverName = "localhost",
        options = coreTestOptions.copy(sessionStore = QuicSessionStore.NotKept),
        factory = BufferFactory.network(),
        api = platformQuicheApi(),
        timeout = LENT_WAIT,
        readPolicy = ReadPolicy.Bounded(LENT_WAIT),
        writePolicy = WritePolicy.Bounded(LENT_WAIT),
        loops = loops,
        ride = ClientRide.Lent(lentChannelOf(channel)),
    )

/** A listener on [channel] (lent) whose connections echo every stream, and the job running its handlers. */
internal suspend fun CoroutineScope.echoingListener(channel: AddressedDatagramChannel): Pair<QuicListener, kotlinx.coroutines.Job> {
    val listener = assertIs<QuicServe.Serving<QuicListener>>(serveQuicOnCore(channel, platformTlsConfig(), coreTestOptions)).value
    return listener to launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
}

/** A listener on its own loopback socket whose connections echo every stream, and the job running its handlers. */
internal suspend fun CoroutineScope.echoingOwnedListener(): Pair<QuicListener, kotlinx.coroutines.Job> {
    val served = serveQuicOnCore(0, LENT_LOOPBACK, platformTlsConfig(), coreTestOptions)
    val listener = assertIs<QuicServe.Serving<QuicListener>>(served).value
    return listener to launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
}

/** What [client]'s lent io dropped for [why]. */
internal fun CoreClient.lentDrops(why: LentDrop): Long = assertIs<IoDrops.Lent>(socket.io).drops.count(why)

/** Whether a connect or serve could claim [channel] now; a claim made to find out is given back at once. */
internal fun claimable(channel: AddressedDatagramChannel): Boolean =
    when (val claimed = LentChannels.claim(channel)) {
        is Claimed.Lent -> true.also { claimed.channel.claim.entryFailed() }
        is Claimed.InUse -> false
    }
