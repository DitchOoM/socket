package com.ditchoom.socket.quic

import com.sun.management.UnixOperatingSystemMXBean
import java.lang.management.ManagementFactory

/**
 * How this test JVM counts the OS handles it holds, which is where a leaked socket shows up: open file
 * descriptors on POSIX, the process handle count on Windows (a Windows socket is a handle).
 */
internal sealed interface OpenHandleMeter {
    /** A human-readable name for the failure message, so a red run says how it was measured. */
    val description: String

    fun openHandles(): Long

    class PosixDescriptors(
        private val bean: UnixOperatingSystemMXBean,
    ) : OpenHandleMeter {
        override val description = "UnixOperatingSystemMXBean.openFileDescriptorCount"

        override fun openHandles(): Long = bean.openFileDescriptorCount
    }

    /**
     * `Get-Process`'s `HandleCount`. Reading it starts a child process, whose pipe handles are closed
     * again before the count is returned, so every reading costs the same and a before/after delta
     * nets it out.
     */
    data object WindowsHandles : OpenHandleMeter {
        override val description = "Get-Process HandleCount"

        override fun openHandles(): Long {
            val pid = ProcessHandle.current().pid()
            val process =
                ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-Process -Id $pid).HandleCount")
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.use { it.bufferedReader().readText().trim() }
            process.outputStream.close()
            check(process.waitFor() == 0 && out.isNotEmpty()) { "Get-Process gave no handle count for pid $pid: $out" }
            return out.toLong()
        }
    }

    companion object {
        /** The meter for this host. */
        fun forThisProcess(): OpenHandleMeter =
            when (val bean = ManagementFactory.getOperatingSystemMXBean()) {
                is UnixOperatingSystemMXBean -> PosixDescriptors(bean)
                else -> WindowsHandles
            }
    }
}
