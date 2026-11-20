package com.ditchoom.socket.quic

import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/**
 * How this test JVM finds what a leaked socket holds: the sockets on POSIX, the UDP endpoints the
 * process owns on Windows.
 *
 * A leak is a handle that appeared during the window and is still open at its end ([Handles.leakedSince]),
 * never a difference of process-wide counts: a closed channel registered with a selector releases its
 * descriptor only at that loop's next `select()`, so descriptors an earlier test closed vanish
 * mid-window and cancel the ones a leak adds.
 */
internal sealed interface OpenHandleMeter {
    /** A human-readable name for the failure message, so a red run says how it was measured. */
    val description: String

    fun openHandles(): Handles

    /** What was open at one instant; compared with an earlier one to find what stayed. */
    sealed interface Handles {
        /** How many handles appeared since [before] and are still open. */
        fun leakedSince(before: Handles): Long

        /** Linux: sockets by kernel inode, which is never reused while its socket lives. */
        class SocketInodes(
            val inodes: Set<String>,
        ) : Handles {
            override fun leakedSince(before: Handles): Long = (inodes - (before as SocketInodes).inodes).size.toLong()

            override fun toString() = "${inodes.size} sockets"
        }

        /** No `/proc`: descriptor numbers. A freed number can be reused by a leak, which hides one no larger than the churn. */
        class Numbers(
            val fds: Set<String>,
        ) : Handles {
            override fun leakedSince(before: Handles): Long = (fds - (before as Numbers).fds).size.toLong()

            override fun toString() = "${fds.size} descriptors"
        }

        /** A bare count, for the platform that can offer nothing finer. */
        class Count(
            val n: Long,
        ) : Handles {
            override fun leakedSince(before: Handles): Long = n - (before as Count).n

            override fun toString() = "$n endpoints"
        }
    }

    data object PosixDescriptors : OpenHandleMeter {
        override val description = "/proc/self/fd socket inodes, else /dev/fd numbers"

        override fun openHandles(): Handles {
            val procFd = File("/proc/self/fd")
            if (!procFd.isDirectory) {
                val devFd = File("/dev/fd")
                // The listing holds a directory descriptor of its own; a directory is no socket.
                return Handles.Numbers(
                    devFd
                        .list()
                        .orEmpty()
                        .filterNot { Files.isDirectory(File(devFd, it).toPath()) }
                        .toSet(),
                )
            }
            return Handles.SocketInodes(
                procFd
                    .list()
                    .orEmpty()
                    .mapNotNull { fd ->
                        // A descriptor closed between the listing and the read is gone.
                        runCatching { Files.readSymbolicLink(File(procFd, fd).toPath()).toString() }
                            .getOrNull()
                            ?.takeIf { it.startsWith("socket:[") }
                    }.toSet(),
            )
        }
    }

    /**
     * The UDP endpoints the process owns, from `Get-NetUDPEndpoint`. Narrower than the process handle
     * count, which also moves with every thread and event the JVM creates lazily; a leaked QUIC path is
     * exactly one of these.
     */
    data object WindowsUdpEndpoints : OpenHandleMeter {
        override val description = "Get-NetUDPEndpoint -OwningProcess"

        override fun openHandles(): Handles {
            val pid = ProcessHandle.current().pid()
            val process =
                ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "@(Get-NetUDPEndpoint -OwningProcess $pid -ErrorAction SilentlyContinue).Count",
                ).redirectErrorStream(true).start()
            val out = process.inputStream.use { it.bufferedReader().readText().trim() }
            process.outputStream.close()
            check(process.waitFor() == 0 && out.isNotEmpty()) { "Get-NetUDPEndpoint gave no count for pid $pid: $out" }
            return Handles.Count(out.toLong())
        }
    }

    companion object {
        /** The meter for this host. */
        fun forThisProcess(): OpenHandleMeter =
            when (ManagementFactory.getOperatingSystemMXBean()) {
                is UnixOperatingSystemMXBean -> PosixDescriptors
                else -> WindowsUdpEndpoints
            }
    }
}

/**
 * Runs [block] with every QUIC event loop held, so each loop's thread and selector (a few descriptors:
 * kqueue or epoll plus its wakeup pipe) are open from before a descriptor baseline to after it. A
 * connection placed on a loop that was idle would otherwise open them mid-measurement and read as a
 * leak. Those descriptors are bounded by the loop count, not by connections.
 */
@OptIn(com.ditchoom.socket.udp.InternalDatagramLoopApi::class)
internal inline fun <T> holdingEveryQuicLoop(block: () -> T): T {
    val holds = quicLoopGroup.loops.map { it.hold() }
    try {
        return block()
    } finally {
        holds.forEach { it.release() }
    }
}

/**
 * Returns once every QUIC event loop has made a selection that began after this call. A closed channel's
 * descriptor is released by its loop's selector in the selection that follows the close, on the loop's
 * thread, so a descriptor count read straight after `close()` returns can still include it. A loop
 * timer is what guarantees the selection: the loop cannot fire it without selecting first.
 */
@OptIn(com.ditchoom.socket.udp.InternalDatagramLoopApi::class)
internal suspend fun everyQuicLoopSelects() {
    for (loop in quicLoopGroup.loops) withContext(loop) { delay(1.milliseconds) }
}
