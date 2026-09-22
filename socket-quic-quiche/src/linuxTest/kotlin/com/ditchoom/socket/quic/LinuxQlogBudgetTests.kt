@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.linuxSockAddrLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

/** Linux K/Native member of [QlogBudgetTestSuite], measured with POSIX directly. */
class LinuxQlogBudgetTests : QlogBudgetTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
            codec = SocketAddressCodec(linuxSockAddrLayout),
        )

    override fun freshDirectory(tag: String): String =
        memScoped {
            // mkdtemp fills the template in place, so it must be a buffer this scope owns.
            val template = "/tmp/$tag-XXXXXX".cstr.getPointer(this)
            mkdtemp(template)?.toKString() ?: error("mkdtemp failed for /tmp/$tag-XXXXXX")
        }

    override fun filesIn(dir: String): Map<String, Long> {
        val handle = opendir(dir) ?: return emptyMap()
        val found = mutableMapOf<String, Long>()
        try {
            memScoped {
                val st = alloc<stat>()
                while (true) {
                    val entry = readdir(handle) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (stat("$dir/$name", st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG) found[name] = st.st_size
                }
            }
        } finally {
            closedir(handle)
        }
        return found
    }

    override fun readText(path: String): String {
        val file = fopen(path, "rb") ?: return ""
        return try {
            memScoped {
                val chunk = allocArray<ByteVar>(CHUNK)
                buildString {
                    while (true) {
                        val n = fread(chunk, 1.convert(), CHUNK.convert(), file).toInt()
                        if (n <= 0) break
                        append(chunk.readBytes(n).decodeToString())
                    }
                }
            }
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
