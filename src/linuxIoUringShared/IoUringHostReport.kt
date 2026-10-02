package com.ditchoom.socket.iouring

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.uname
import platform.posix.utsname

/**
 * What the host looked like when an `io_uring_setup` failed: the kernel, the memlock budget rings are
 * charged against on older kernels, how much the process has locked, its memory and fds. An `ENOMEM`
 * names only the errno; these, with the manager's own ring ledger, say whether it was a leak or an
 * exhausted budget, and none of them can be read once the process is gone. Failure path only.
 *
 * Every line is best-effort and says so when a source is unreadable, so a sandbox without `/proc`
 * degrades to a shorter report rather than a second failure inside the first.
 */
internal fun ioUringHostReport(): String =
    buildString {
        for (section in HOST_REPORT_SECTIONS) appendLine("  ${section.label}: ${section.read()}")
    }.trimEnd()

/** One line of [ioUringHostReport]: what it is called, and how to read it now. */
internal class HostReportSection(
    val label: String,
    val read: () -> String,
)

/**
 * The report's lines, in order. Declared as data, one entry per section, so a repeated section is a
 * repeated label that IoUringHostReportTests rejects — not a stray `appendLine` an edit or a merge can
 * duplicate unnoticed.
 */
@OptIn(ExperimentalForeignApi::class)
internal val HOST_REPORT_SECTIONS: List<HostReportSection> =
    listOf(
        HostReportSection("kernel") {
            memScoped {
                val u = alloc<utsname>()
                if (uname(u.ptr) == 0) {
                    "${u.release.toKString()} (${u.version.toKString()}) ${u.machine.toKString()}"
                } else {
                    "uname failed"
                }
            }
        },
        // /proc/self/limits rather than getrlimit(2): Kotlin/Native's posix bindings for Linux do not
        // expose sys/resource.h, and the text file carries the same soft/hard pair.
        HostReportSection("/proc/self/limits") { procLines("/proc/self/limits", listOf("Max locked memory", "Max open files")) },
        HostReportSection("/proc/self/status") {
            procLines("/proc/self/status", listOf("VmLck", "VmRSS", "VmSize", "Threads", "FDSize"))
        },
        HostReportSection("/proc/meminfo") { procLines("/proc/meminfo", listOf("MemAvailable", "Committed_AS", "Mlocked")) },
        HostReportSection("open fds") { openFdCount() },
        HostReportSection("io_uring rings of this user") { ioUringRingsOfThisUser() },
        HostReportSection("/proc/sys/kernel/io_uring_disabled") { procFirstLine("/proc/sys/kernel/io_uring_disabled") },
    )

/** The rows of a `/proc` text file that start with one of [keys], whitespace-collapsed, or why it could not be read. */
@OptIn(ExperimentalForeignApi::class)
private fun procLines(
    path: String,
    keys: List<String>,
): String {
    val fp: CPointer<FILE> = fopen(path, "r") ?: return "unreadable"
    val found = ArrayList<String>(keys.size)
    try {
        memScoped {
            val line = allocArray<ByteVar>(PROC_LINE_CAPACITY)
            while (fgets(line, PROC_LINE_CAPACITY, fp) != null) {
                val text = line.toKString().trimEnd()
                if (keys.any { text.startsWith(it) }) found += text.replace(Regex("\\s+"), " ")
            }
        }
    } finally {
        fclose(fp)
    }
    return found.joinToString("; ").ifEmpty { "none of $keys present" }
}

@OptIn(ExperimentalForeignApi::class)
private fun procFirstLine(path: String): String {
    val fp: CPointer<FILE> = fopen(path, "r") ?: return "absent"
    try {
        memScoped {
            val line = allocArray<ByteVar>(PROC_LINE_CAPACITY)
            return fgets(line, PROC_LINE_CAPACITY, fp)?.toKString()?.trim() ?: "empty"
        }
    } finally {
        fclose(fp)
    }
}

/** Entries under `/proc/self/fd`, minus `.`/`..` and the directory handle itself. */
@OptIn(ExperimentalForeignApi::class)
private fun openFdCount(): String {
    val dir = opendir("/proc/self/fd") ?: return "unreadable"
    var n = 0
    try {
        while (readdir(dir) != null) n++
    } finally {
        closedir(dir)
    }
    return (n - 3).coerceAtLeast(0).toString()
}

private const val PROC_LINE_CAPACITY = 512
