package com.ditchoom.socket

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.coroutines.resume

internal actual fun platformHostResolver(): HostResolver = AndroidResolverStrategy.chosen.resolver

/** How this device resolves, chosen once: the system's asynchronous resolver where it has one. */
internal sealed interface AndroidResolverStrategy {
    val resolver: HostResolver

    /** API 29 and later: [DnsResolver] queries on the default network and calls back; no thread waits. */
    data object SystemAsync : AndroidResolverStrategy {
        override val resolver: HostResolver = SystemDnsResolver
    }

    /** API 28, which has only the blocking `InetAddress` lookup, run detached. */
    data object Detached : AndroidResolverStrategy {
        override val resolver: HostResolver = JvmHostResolver
    }

    companion object {
        val chosen: AndroidResolverStrategy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SystemAsync else Detached
    }
}

@SuppressLint("NewApi")
private object SystemDnsResolver : HostResolver {
    override suspend fun resolve(host: String): Resolution =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(
                null,
                host,
                DnsResolver.FLAG_EMPTY,
                Runnable::run,
                signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(
                        answer: List<InetAddress>,
                        rcode: Int,
                    ) {
                        val records =
                            answer.map {
                                ResolvedAddress(it.hostAddress.orEmpty(), if (it is Inet6Address) IpFamily.V6 else IpFamily.V4)
                            }
                        continuation.resume(resolvedInOrder(records, host))
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.resume(Resolution.Failed(host, failureOf(error)))
                    }
                },
            )
        }

    /** A system failure names its errno; a response the resolver could not parse is how an NXDOMAIN arrives. */
    private fun failureOf(error: DnsResolver.DnsException): ResolveFailure {
        val errno = (error.cause as? ErrnoException)?.errno
        return when {
            error.code == DnsResolver.ERROR_PARSE -> ResolveFailure.NotFound
            errno == OsConstants.ETIMEDOUT -> ResolveFailure.TimedOut
            errno == OsConstants.ENETUNREACH || errno == OsConstants.ENONET || errno == OsConstants.EHOSTUNREACH -> ResolveFailure.NoNetwork
            errno != null -> ResolveFailure.Os(errno)
            else -> ResolveFailure.Os(error.code)
        }
    }
}
