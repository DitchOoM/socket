package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

internal actual fun platformHostResolver(): HostResolver = JvmHostResolver

/** The JDK resolver, every record: `getAllByName`, off the caller's thread and cancellable. */
internal object JvmHostResolver : HostResolver {
    override suspend fun resolve(host: String): Resolution =
        try {
            val records = runInterruptible(Dispatchers.IO) { InetAddress.getAllByName(host) }
            resolvedInOrder(
                records.map { ResolvedAddress(it.hostAddress, if (it is Inet6Address) IpFamily.V6 else IpFamily.V4) },
                host,
            )
        } catch (e: UnknownHostException) {
            Resolution.Failed(host, e)
        }
}
