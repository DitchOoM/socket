package com.ditchoom.socket.udp

import java.io.File
import java.nio.file.Files

/**
 * What this process holds open, taken so that a leak can be told from churn.
 *
 * A process-wide *count* cannot: a closed `DatagramChannel` registered with a selector gives its
 * descriptor back only at that loop's next `select()`, so descriptors an earlier test closed vanish
 * mid-window and cancel out (or exceed) the ones a leak adds. A leak is a descriptor that appeared
 * during the window and was still open at its end, which is [appearedSince], not a difference of counts.
 */
internal sealed interface OpenDescriptors {
    /** What identifies a descriptor in a failure message. */
    val description: String

    /** The descriptors open now that were not in [before], as the owner would see them. */
    fun appearedSince(before: OpenDescriptors): List<String>

    /**
     * Linux: every socket by its kernel inode (`socket:[…]` in `/proc/self/fd`). An inode is never
     * reused while its socket lives, so churn cannot be mistaken for a leak, nor hide one.
     */
    class Sockets(
        private val inodes: Set<String>,
    ) : OpenDescriptors {
        override val description = "/proc/self/fd socket inodes"

        override fun appearedSince(before: OpenDescriptors): List<String> = (inodes - (before as Sockets).inodes).sorted()
    }

    /**
     * No `/proc`: descriptor numbers from `/dev/fd`. A number a closed descriptor freed can be handed to
     * a leaked one, so this hides a leak no larger than the churn in its window; a leak per attempt is
     * still unmistakable.
     */
    class Numbers(
        private val fds: Set<String>,
    ) : OpenDescriptors {
        override val description = "/dev/fd descriptor numbers"

        override fun appearedSince(before: OpenDescriptors): List<String> =
            (fds - (before as Numbers).fds).sortedBy { it.toInt() }.map { "fd $it" }
    }

    companion object {
        private val procFd = File("/proc/self/fd")
        private val devFd = File("/dev/fd")

        fun capture(): OpenDescriptors =
            if (procFd.isDirectory) {
                Sockets(
                    procFd
                        .list()
                        .orEmpty()
                        .mapNotNull { fd ->
                            // A descriptor closed between the listing and the read (the listing's own, for one) is gone.
                            runCatching { Files.readSymbolicLink(File(procFd, fd).toPath()).toString() }
                                .getOrNull()
                                ?.takeIf { it.startsWith("socket:[") }
                        }.toSet(),
                )
            } else {
                // The listing holds a directory descriptor of its own, at whatever number was free; a
                // directory is no socket, so leaving them out keeps the listing from reading as a leak.
                Numbers(
                    devFd
                        .list()
                        .orEmpty()
                        .filterNot { Files.isDirectory(File(devFd, it).toPath()) }
                        .toSet(),
                )
            }
    }
}
