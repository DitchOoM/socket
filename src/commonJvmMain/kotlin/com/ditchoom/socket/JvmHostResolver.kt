package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

internal actual fun platformHostResolver(): HostResolver = JvmHostResolver

/**
 * The JDK resolver, every record: `getAllByName`, on an IO thread the caller can cancel its wait for. The
 * lookup itself cannot be interrupted (the JDK sits in the native resolver), so see [BlockingHostResolver].
 */
internal val JvmHostResolver: HostResolver =
    BlockingHostResolver(Dispatchers.IO) { host ->
        try {
            val records = InetAddress.getAllByName(host)
            resolvedInOrder(
                records.map { ResolvedAddress(it.hostAddress, if (it is Inet6Address) IpFamily.V6 else IpFamily.V4) },
                host,
            )
        } catch (e: UnknownHostException) {
            Resolution.Failed(host, e)
        }
    }
