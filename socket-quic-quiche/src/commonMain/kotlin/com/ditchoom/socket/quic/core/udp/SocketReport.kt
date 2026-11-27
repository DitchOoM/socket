package com.ditchoom.socket.quic.core.udp

/** The datagrams the operating system dropped for one socket because its receive buffer was full. */
internal sealed interface SocketDrops {
    data class Counted(
        val datagrams: Long,
    ) : SocketDrops

    /** The operating system keeps no per-socket count here, or it could not be read. */
    data object NotAvailable : SocketDrops
}

/** One UDP socket's granted buffers, and its drop count read on demand; nothing is read until [drops] is called. */
internal class SocketReport(
    val granted: Granted,
    private val localPort: Int,
    private val tables: List<String> = LINUX_UDP_TABLES,
    /** What the socket's own io drops. */
    val io: IoDrops = IoDrops.Kernel,
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
            when (val read = readTable(table)) {
                is TableRead.Lines -> {
                    seen = true
                    total += dropsFor(read.lines, localPort)
                }
                is TableRead.Unreadable -> Unit
            }
        }
        return if (seen) SocketDrops.Counted(total) else SocketDrops.NotAvailable
    }

    private companion object {
        val LINUX_UDP_TABLES: List<String> = listOf("/proc/net/udp", "/proc/net/udp6")
    }
}

/** What reading a kernel table answered. */
internal sealed interface TableRead {
    class Lines(
        val lines: List<String>,
    ) : TableRead

    /** The file does not exist here, or could not be read. */
    data object Unreadable : TableRead
}

/** The lines of the text file at [path]. Reads the file: never on a hot path. */
internal expect fun readTable(path: String): TableRead

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
