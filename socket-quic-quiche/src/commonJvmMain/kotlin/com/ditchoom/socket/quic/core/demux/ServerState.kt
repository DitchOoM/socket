@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.NativeSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.conn.DatagramSlot
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopClosed
import com.ditchoom.socket.quic.core.pool.FixedPool
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

/** Memory shared by the loop and the connections on it, freed when the last of them has let go. */
internal class ServerResources(
    private val freeAll: () -> Unit,
) {
    private val holders = AtomicInt(1)

    fun acquire() {
        holders.fetchAndAdd(1)
    }

    fun release() {
        if (holders.fetchAndAdd(-1) == 1) freeAll()
    }
}

internal sealed interface Attached {
    object Nothing : Attached

    class To(
        val loop: Loop,
    ) : Attached
}

/** Whether the server accepts connections, or is closing and will not. */
internal sealed interface Shutdown {
    object Running : Shutdown

    /** Connections were told to close in the pass numbered [startedPass]; the loop stops once they are flushed. */
    class Draining(
        val startedPass: Long,
        val closed: LoopClosed,
    ) : Shutdown

    object Finished : Shutdown
}

/** What a server needs besides its loop to take connections. */
internal class ServerSetup(
    val api: QuicheApi,
    val config: QuicheConfig,
    val factory: BufferFactory,
    val serverConfig: ServerConfig,
    val local: NativeSockAddr,
    val localKey: PathKey,
    val readPolicy: ReadPolicy,
    val writePolicy: WritePolicy,
    val clock: LoopClock,
    val random: Random,
    val slots: FixedPool<DatagramSlot>,
    val resources: ServerResources,
    val accepted: Channel<CoreConnection>,
    val counts: ServerCounts,
    /** The largest datagram the server's socket delivers. */
    val datagramSize: Int,
)

internal sealed interface Tokens {
    object None : Tokens

    class Of(
        val tokens: AddressTokens,
    ) : Tokens
}

/** Loop work that does nothing but guarantee another pass. One per server: a work item sits in one inbox at a time. */
internal class Tick : com.ditchoom.socket.quic.core.loop.LoopWork() {
    override fun run(loop: com.ditchoom.socket.quic.core.loop.LoopHost) = Unit

    override fun gone() = Unit
}

internal fun hexOf(cid: CidRead): String {
    val out = StringBuilder(cid.length * 2)
    for (i in 0 until cid.length) out.append(HEX[cid.byteAt(i) ushr 4]).append(HEX[cid.byteAt(i) and 0xF])
    return out.toString()
}

internal fun literalOf(address: InetSocketAddress): String = address.address.hostAddress.substringBefore('%')

private const val HEX = "0123456789abcdef"

/** How the new connection's id and original destination id are decided. */
internal sealed interface Admit {
    /** No address validation: the server picks the id and also answers to the id the client chose. */
    object Fresh : Admit

    /** A valid token: the client already uses the id the server picked, in the packet's destination id. */
    class Redeemed(
        val token: TokenCheck.Valid,
    ) : Admit
}

internal sealed interface Created {
    object Refused : Created

    class Peer(
        val peer: ServerPeer,
    ) : Created
}
