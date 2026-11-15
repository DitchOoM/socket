package com.ditchoom.socket.quic.core.udp

import java.io.IOException
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The socket buffer sizes asked of the operating system for a UDP socket, in bytes. The receive buffer has to hold
 * what the sender emits in one burst until the loop reads it: the default's 4 MiB is about a 15 ms round trip at
 * the wire speed of a 2.5 Gb/s link, and an operating system default of about 200 KiB overflows long before
 * that. The send buffer only has to hold what one pass hands the kernel, with room for the loop's lanes: 2 MiB is
 * about 1,400 datagrams.
 */
internal class SocketBuffers(
    val receiveBytes: Int,
    val sendBytes: Int,
) {
    init {
        require(receiveBytes > 0 && sendBytes > 0)
    }

    /** Asks for these sizes on [channel] and reads back what the operating system granted. */
    fun applyTo(channel: DatagramChannel): GrantedBuffers {
        channel.setOption(StandardSocketOptions.SO_RCVBUF, receiveBytes)
        channel.setOption(StandardSocketOptions.SO_SNDBUF, sendBytes)
        return GrantedBuffers(
            requested = this,
            receiveBytes = channel.getOption(StandardSocketOptions.SO_RCVBUF),
            sendBytes = channel.getOption(StandardSocketOptions.SO_SNDBUF),
        )
    }

    companion object {
        val Default = SocketBuffers(receiveBytes = 4 shl 20, sendBytes = 2 shl 20)
    }
}

/**
 * What the operating system answered when asked for [requested]. Linux answers twice the value it accepted, capped
 * by `net.core.rmem_max` and `net.core.wmem_max`; macOS answers what it set, capped by `kern.ipc.maxsockbuf`.
 */
internal class GrantedBuffers(
    val requested: SocketBuffers,
    val receiveBytes: Int,
    val sendBytes: Int,
)

/** The datagrams the operating system dropped for one socket because its receive buffer was full. */
internal sealed interface SocketDrops {
    class Counted(
        val datagrams: Long,
    ) : SocketDrops

    /** The operating system keeps no per-socket count here, or it could not be read. */
    object NotAvailable : SocketDrops
}

/** One UDP socket's granted buffers, and its drop count read on demand; nothing is read until [drops] is called. */
internal class SocketReport(
    val granted: GrantedBuffers,
    private val localPort: Int,
    private val tables: List<Path> = LINUX_UDP_TABLES,
) {
    /**
     * The drops of this socket: the sum over the rows of `/proc/net/udp` and `/proc/net/udp6` whose local port is
     * this socket's (each socket has its own port; a dual-stack socket is only in the second table).
     * [SocketDrops.NotAvailable] where those tables do not exist. Reads two small files: never call it on a hot path.
     */
    fun drops(): SocketDrops {
        var total = 0L
        var seen = false
        for (table in tables) {
            val lines =
                try {
                    Files.readAllLines(table)
                } catch (unreadable: IOException) {
                    continue
                }
            seen = true
            total += dropsFor(lines, localPort)
        }
        return if (seen) SocketDrops.Counted(total) else SocketDrops.NotAvailable
    }

    private companion object {
        val LINUX_UDP_TABLES: List<Path> = listOf(Paths.get("/proc/net/udp"), Paths.get("/proc/net/udp6"))
    }
}

/**
 * The drops of the rows of a `/proc/net/udp` table whose local port is [port]. A row is
 * `sl local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode ref pointer drops`, with the local address
 * as `HEX_ADDRESS:HEX_PORT`; the header row is skipped.
 */
internal fun dropsFor(
    table: List<String>,
    port: Int,
): Long {
    var total = 0L
    for (index in 1 until table.size) {
        val columns = table[index].trim().split(WHITESPACE)
        if (columns.size <= DROPS_COLUMN) continue
        val local = columns[LOCAL_COLUMN]
        val localPort = local.substring(local.lastIndexOf(':') + 1).toIntOrNull(radix = 16) ?: continue
        if (localPort != port) continue
        total += columns[DROPS_COLUMN].toLongOrNull() ?: continue
    }
    return total
}

private val WHITESPACE = Regex("\\s+")
private const val LOCAL_COLUMN = 1
private const val DROPS_COLUMN = 12
