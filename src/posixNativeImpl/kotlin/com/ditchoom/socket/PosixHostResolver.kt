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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.INET6_ADDRSTRLEN
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.inet_ntop
import platform.posix.memset
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

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
                    entry.ai_addr?.let { collect(it, records) }
                    node = entry.ai_next
                }
                resolvedInOrder(records, host)
            } finally {
                freeaddrinfo(head)
            }
        }

    private fun MemScope.collect(
        address: CPointer<sockaddr>,
        into: MutableList<ResolvedAddress>,
    ) {
        val text = allocArray<ByteVar>(INET6_ADDRSTRLEN)
        when (address.pointed.sa_family.toInt()) {
            AF_INET -> {
                val in4 = address.reinterpret<sockaddr_in>().pointed
                inet_ntop(AF_INET, in4.sin_addr.ptr, text, INET6_ADDRSTRLEN.convert())?.let {
                    into += ResolvedAddress(it.toKString(), IpFamily.V4)
                }
            }
            AF_INET6 -> {
                val in6 = address.reinterpret<sockaddr_in6>().pointed
                inet_ntop(AF_INET6, in6.sin6_addr.ptr, text, INET6_ADDRSTRLEN.convert())?.let {
                    into += ResolvedAddress(it.toKString(), IpFamily.V6)
                }
            }
        }
    }
}
