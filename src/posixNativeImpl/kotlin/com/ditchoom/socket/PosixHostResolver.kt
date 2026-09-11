@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.memset
import platform.posix.sockaddr
import platform.posix.socklen_t

/** The POSIX resolver: `getaddrinfo`, every record, off the caller's dispatcher. A link-local scope is not carried. */
internal object PosixHostResolver : HostResolver {
    override suspend fun resolve(host: String): Resolution = withContext(Dispatchers.Default) { lookup(host) }

    private fun lookup(host: String): Resolution =
        memScoped {
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            val result = allocPointerTo<addrinfo>()
            val ret = getaddrinfo(host, null, hints.ptr, result.ptr)
            if (ret != 0) {
                val reason = gai_strerror(ret)?.toKString() ?: "getaddrinfo returned $ret"
                return@memScoped Resolution.Failed(host, SocketUnknownHostException(host, reason))
            }
            val head = result.value ?: return@memScoped Resolution.NoAddress(host)
            try {
                val records = ArrayList<ResolvedAddress>()
                var node: CPointer<addrinfo>? = head
                while (node != null) {
                    val entry = node.pointed
                    entry.ai_addr?.let { collect(it, entry.ai_addrlen, records) }
                    node = entry.ai_next
                }
                resolvedInOrder(records, host)
            } finally {
                freeaddrinfo(head)
            }
        }

    /** `getnameinfo` with `NI_NUMERICHOST` renders the literal for either family, scope included. */
    private fun MemScope.collect(
        address: CPointer<sockaddr>,
        length: socklen_t,
        into: MutableList<ResolvedAddress>,
    ) {
        val family =
            when (address.pointed.sa_family.toInt()) {
                AF_INET -> IpFamily.V4
                AF_INET6 -> IpFamily.V6
                else -> return
            }
        val text = allocArray<ByteVar>(NI_MAXHOST)
        if (getnameinfo(address, length, text, NI_MAXHOST.convert(), null, 0.convert(), NI_NUMERICHOST) == 0) {
            into += ResolvedAddress(text.toKString(), family)
        }
    }
}
