package com.ditchoom.socket.udp

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
 * What the host looked like at the moment an `io_uring_setup` failed — the part of an `ENOMEM` that
 * cannot be reconstructed after the process is gone.
 *
 * WHY: #561 is `io_uring_setup` → `ENOMEM` at ring creation on the Linux CI lane, twice, on
 * unrelated PRs, in whichever test happened to create the next ring. The message named the errno
 * and told the reader to run `uname -r`; nothing recorded the kernel, the memlock budget rings are
 * charged against on older kernels, how much the process had locked, or how many rings this manager
 * had created and released — so the two things the issue says must be established before any fix
 * were not in the report. This gathers them at the failure site, from `/proc` and the rlimits, and
 * the manager appends its own ring ledger. Read only on the failure path; never on a hot path.
 *
 * Every line is best-effort and says so when a source is unreadable, so a sandbox without `/proc`
 * degrades to a shorter report rather than a second failure inside the first.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ioUringHostReport(): String =
    buildString {
        memScoped {
            val u = alloc<utsname>()
            if (uname(u.ptr) == 0) {
                appendLine("  kernel: ${u.release.toKString()} (${u.version.toKString()}) ${u.machine.toKString()}")
            } else {
                appendLine("  kernel: uname failed")
            }
        }
        // /proc/self/limits rather than getrlimit(2): Kotlin/Native's posix bindings for Linux do not
        // expose sys/resource.h, and the text file carries the same soft/hard pair.
        appendLine("  /proc/self/limits: " + procLines("/proc/self/limits", listOf("Max locked memory", "Max open files")))
        appendLine("  /proc/self/status: " + procLines("/proc/self/status", listOf("VmLck", "VmRSS", "VmSize", "Threads", "FDSize")))
        appendLine("  /proc/meminfo: " + procLines("/proc/meminfo", listOf("MemAvailable", "Committed_AS", "Mlocked")))
        appendLine("  open fds: ${openFdCount()}")
        appendLine("  /proc/sys/kernel/io_uring_disabled: " + procFirstLine("/proc/sys/kernel/io_uring_disabled"))
    }.trimEnd()

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
