package com.ditchoom.socket

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal actual fun platformHostResolver(): HostResolver = NodeHostResolver

/** Node's `dns.lookup` with every record; a browser has no resolver to offer. */
internal object NodeHostResolver : HostResolver {
    override suspend fun resolve(host: String): Resolution {
        if (!isNodeJs) return Resolution.Failed(host, UnsupportedOperationException("a browser exposes no resolver"))
        return suspendCancellableCoroutine { cont ->
            lookupAll(host) { error, records ->
                if (cont.isCompleted) return@lookupAll
                if (error != null) {
                    cont.resume(Resolution.Failed(host, RuntimeException(error.toString())))
                } else {
                    val list =
                        (records as Array<dynamic>).map {
                            ResolvedAddress(
                                it.address as String,
                                if ((it.family as Int) ==
                                    6
                                ) {
                                    IpFamily.V6
                                } else {
                                    IpFamily.V4
                                },
                            )
                        }
                    cont.resume(resolvedInOrder(list, host))
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun lookupAll(
    host: String,
    callback: (dynamic, dynamic) -> Unit,
) {
    js("require('dns').lookup(host, { all: true, verbatim: true }, callback)")
}
