package com.ditchoom.socket.quic.core.sched

internal actual val kernelOfThisRuntime: HostKernel = kernelOfOsName(System.getProperty("os.name"))

/** The kernel a JVM's `os.name` names. */
internal fun kernelOfOsName(osName: String): HostKernel {
    val name = osName.lowercase()
    return when {
        name.startsWith("mac") || name.startsWith("darwin") -> HostKernel.Darwin
        name.startsWith("linux") -> HostKernel.Linux
        name.startsWith("windows") -> HostKernel.Windows
        else -> HostKernel.Other
    }
}
