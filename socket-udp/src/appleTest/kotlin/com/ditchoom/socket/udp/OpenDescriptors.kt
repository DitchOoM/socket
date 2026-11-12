@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

/**
 * The number of file descriptors this process holds right now.
 *
 * Counts the numeric entries of `/dev/fd`, which the kernel builds from the process's descriptor
 * table at the moment of the listing, so it sees every kind of descriptor (sockets, kqueues, pipes)
 * and needs no limit to be hit. The directory stream holds one descriptor itself; it is not counted.
 */
internal fun openDescriptorCount(): Int {
    val directory = opendir("/dev/fd") ?: error("cannot list /dev/fd")
    var entries = 0
    try {
        while (true) {
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name.isNotEmpty() && name.all { it.isDigit() }) entries++
        }
    } finally {
        closedir(directory)
    }
    return entries - 1
}
