@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.core.udp.Arrival
import com.ditchoom.socket.udp.nw.nw_udp_cancel
import com.ditchoom.socket.udp.nw.nw_udp_copy_local_sockaddr
import com.ditchoom.socket.udp.nw.nw_udp_copy_remote_sockaddr
import com.ditchoom.socket.udp.nw.nw_udp_create_dont_fragment
import com.ditchoom.socket.udp.nw.nw_udp_receive
import com.ditchoom.socket.udp.nw.nw_udp_send
import com.ditchoom.socket.udp.nw.nw_udp_set_state_handler
import com.ditchoom.socket.udp.nw.nw_udp_start
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCPointer
import platform.Foundation.NSData
import platform.Network.nw_connection_batch
import platform.Network.nw_connection_t
import platform.posix.memcpy

/**
 * A client's Network.framework UDP connection through the `NwUdp` helpers of `:socket-udp`: plain UDP, no DTLS, on
 * Network.framework's own queue. The C surface's absent values end here; what leaves is typed.
 */
internal class NwConnection private constructor(
    private val conn: nw_connection_t,
) {
    /**
     * Starts it; [changed] is told every state change: `nw_connection_state_t`, error domain, error code, path status,
     * and the path's `nw_path_unsatisfied_reason_t`.
     */
    fun start(changed: (Int, Int, Int, Int, Int) -> Unit) {
        nw_udp_set_state_handler(conn) { state, domain, code, _, path, reason, _ -> changed(state, domain, code, path, reason) }
        nw_udp_start(conn)
    }

    fun cancel() = nw_udp_cancel(conn)

    /** What arms one receive; each completed receive is handed to [arrived] as one [Arrival], on Network.framework's queue. */
    fun receiver(arrived: (Arrival) -> Unit): () -> Unit {
        val handler: (NSData?, Int, Int) -> Unit = { content, _, code ->
            arrived(if (content == null || code != 0) Arrival.Ended else Arrival.Datagram(content))
        }
        return { nw_udp_receive(conn, handler) }
    }

    /** What sends one datagram of `length` bytes at an address; [completed] runs when Network.framework is done with it. */
    fun sender(completed: () -> Unit): (Long, Int) -> Unit {
        val handler: (Int, Int) -> Unit = { _, _ -> completed() }
        return { address, length ->
            when (val at = address.toCPointer<ByteVar>()) {
                null -> completed()
                else -> nw_udp_send(conn, at, length, handler)
            }
        }
    }

    /** Runs [block] at once, with its sends handed to the network together. */
    fun batch(block: () -> Unit) = nw_connection_batch(conn, block)

    /** The address the connection's path leaves from; the wildcard of the peer's family while none is known. */
    fun local(peer: PathKey): PathKey = addressOf(peer, local = true)

    fun remote(peer: PathKey): PathKey = addressOf(peer, local = false)

    private fun addressOf(
        peer: PathKey,
        local: Boolean,
    ): PathKey =
        memScoped {
            val storage = allocArray<ByteVar>(SOCKADDR_STORAGE)
            val length =
                if (local) {
                    nw_udp_copy_local_sockaddr(
                        conn,
                        storage,
                        SOCKADDR_STORAGE,
                    )
                } else {
                    nw_udp_copy_remote_sockaddr(conn, storage, SOCKADDR_STORAGE)
                }
            val port = ((storage[2].toInt() and 0xFF) shl 8) or (storage[3].toInt() and 0xFF)
            when {
                length <= 0 -> PathKey(peer.family, 0, 0L, 0L)
                storage[1].toInt() == AF_INET6_DARWIN -> PathKey(6, port, bigEndian(storage, V6_AT), bigEndian(storage, V6_AT + 8))
                else -> {
                    var v4 = 0L
                    for (i in V4_AT until V4_AT + 4) v4 = (v4 shl 8) or (storage[i].toLong() and 0xFF)
                    PathKey(4, port, 0L, v4)
                }
            }
        }

    private fun bigEndian(
        storage: kotlinx.cinterop.CPointer<ByteVar>,
        from: Int,
    ): Long {
        var value = 0L
        for (i in from until from + 8) value = (value shl 8) or (storage[i].toLong() and 0xFF)
        return value
    }

    companion object {
        /** A connection to [host]:[port] whose datagrams carry DF (RFC 9000 §14), not started; [NwCreated.Refused] when Network.framework would not make one. */
        fun create(
            host: String,
            port: Int,
        ): NwCreated =
            when (val conn = nw_udp_create_dont_fragment(host, port.toString())) {
                null -> NwCreated.Refused
                else -> NwCreated.Created(NwConnection(conn))
            }

        private const val SOCKADDR_STORAGE = 128
        private const val AF_INET6_DARWIN = 30
        private const val V4_AT = 4
        private const val V6_AT = 8
    }
}

/** What [NwConnection.create] answered. */
internal sealed interface NwCreated {
    class Created(
        val connection: NwConnection,
    ) : NwCreated

    data object Refused : NwCreated
}

/** Copies [content] into native memory at [address], at most [capacity] bytes; returns how many it copied. */
internal fun copyOut(
    content: NSData,
    address: Long,
    capacity: Int,
): Int {
    val length = minOf(content.length.toInt(), capacity)
    memcpy(address.toCPointer<ByteVar>(), content.bytes, length.convert())
    return length
}
