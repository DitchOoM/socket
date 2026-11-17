package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The JDK resolver, every record: `getAllByName`, on one of [RESOLVER_PARALLELISM] threads the caller can
 * cancel its wait for. The lookup itself cannot be interrupted (the JDK sits in the native resolver), so
 * see [BlockingHostResolver].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val JvmHostResolver: HostResolver =
    BlockingHostResolver(Dispatchers.IO.limitedParallelism(RESOLVER_PARALLELISM)) { host ->
        try {
            val records = InetAddress.getAllByName(host)
            resolvedInOrder(
                records.map { ResolvedAddress(it.hostAddress, if (it is Inet6Address) IpFamily.V6 else IpFamily.V4) },
                host,
            )
        } catch (_: UnknownHostException) {
            Resolution.Failed(host, ResolveFailure.NotFound)
        }
    }
