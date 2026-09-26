package com.ditchoom.socket.quic

import com.sun.management.UnixOperatingSystemMXBean
import java.lang.management.ManagementFactory

/**
 * How this test JVM counts what a leaked socket holds: open file descriptors on POSIX, the UDP endpoints
 * the process owns on Windows.
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
     * The UDP endpoints the process owns, from `Get-NetUDPEndpoint`. Narrower than the process handle
     * count, which also moves with every thread and event the JVM creates lazily; a leaked QUIC path is
     * exactly one of these.
     */
    data object WindowsUdpEndpoints : OpenHandleMeter {
        override val description = "Get-NetUDPEndpoint -OwningProcess"

        override fun openHandles(): Long {
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
            return out.toLong()
        }
    }

    companion object {
        /** The meter for this host. */
        fun forThisProcess(): OpenHandleMeter =
            when (val bean = ManagementFactory.getOperatingSystemMXBean()) {
                is UnixOperatingSystemMXBean -> PosixDescriptors(bean)
                else -> WindowsUdpEndpoints
            }
    }
}
