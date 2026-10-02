package com.ditchoom.socket.iouring

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getuid
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.readlink

/**
 * Every process of this user holding io_uring rings, with each ring's submission/completion entries.
 *
 * WHY: ring memory is charged to a budget shared by every process of the user (`RLIMIT_MEMLOCK`, 8 MiB on a
 * GitHub runner; a 1024-entry ring costs ~105 KiB of it), so an `ENOMEM` from `io_uring_setup` can be
 * another process's doing. The failing process's own ledger cannot show that; this names the other
 * tenants at the moment of failure. Best-effort: a process that exits mid-walk is skipped.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ioUringRingsOfThisUser(): String {
    val uid = getuid().toString()
    val proc = opendir("/proc") ?: return "unreadable"
    val holders = ArrayList<String>()
    var rings = 0
    try {
        while (true) {
            val pid = readdir(proc)?.pointed?.d_name?.toKString() ?: break
            if (pid.isEmpty() || !pid.all(Char::isDigit) || realUid(pid) != uid) continue
            val sizes = ringSizes(pid)
            if (sizes.isEmpty()) continue
            rings += sizes.size
            holders += "${firstLine("/proc/$pid/comm")}($pid)=${sizes.joinToString("+")}"
        }
    } finally {
        closedir(proc)
    }
    return "$rings ring(s) in ${holders.size} process(es)" + if (holders.isEmpty()) "" else ": " + holders.joinToString(", ")
}

/** The real uid on the `Uid:` row of `/proc/<pid>/status`, or `null` if the process is gone. */
private fun realUid(pid: String): String? =
    lines("/proc/$pid/status").firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)

/** `sq<entries>/cq<entries>` for each io_uring descriptor [pid] holds. */
@OptIn(ExperimentalForeignApi::class)
private fun ringSizes(pid: String): List<String> {
    val fds = opendir("/proc/$pid/fd") ?: return emptyList()
    val sizes = ArrayList<String>()
    try {
        memScoped {
            val target = allocArray<ByteVar>(LINK_CAPACITY)
            while (true) {
                val fd = readdir(fds)?.pointed?.d_name?.toKString() ?: break
                if (fd.isEmpty() || !fd.all(Char::isDigit)) continue
                val n = readlink("/proc/$pid/fd/$fd", target, (LINK_CAPACITY - 1).toULong())
                if (n <= 0) continue
                target[n.toInt()] = 0
                if (target.toKString() != "anon_inode:[io_uring]") continue
                val info = lines("/proc/$pid/fdinfo/$fd")
                sizes += "sq${entries(info, "SqMask:")}/cq${entries(info, "CqMask:")}"
            }
        }
    } finally {
        closedir(fds)
    }
    return sizes
}

/** A ring's entry count from its `fdinfo` mask row (`SqMask:\t0x3ff` is 1024 entries), or `?`. */
private fun entries(
    info: List<String>,
    key: String,
): String =
    info.firstOrNull { it.startsWith(key) }
        ?.substringAfter(key)
        ?.trim()
        ?.removePrefix("0x")
        ?.toLongOrNull(16)
        ?.let { (it + 1).toString() }
        ?: "?"

@OptIn(ExperimentalForeignApi::class)
private fun lines(path: String): List<String> {
    val fp = fopen(path, "r") ?: return emptyList()
    val out = ArrayList<String>()
    try {
        memScoped {
            val line = allocArray<ByteVar>(LINE_CAPACITY)
            while (fgets(line, LINE_CAPACITY, fp) != null) out += line.toKString().trimEnd()
        }
    } finally {
        fclose(fp)
    }
    return out
}

private fun firstLine(path: String): String = lines(path).firstOrNull() ?: "?"

private const val LINK_CAPACITY = 256
private const val LINE_CAPACITY = 512
