package com.ditchoom.socket

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal actual fun platformHostResolver(): HostResolver = NodeHostResolver

/** Node's `dns.lookup` with every record; a browser has no resolver to offer. */
internal object NodeHostResolver : HostResolver {
    override suspend fun resolve(host: String): Resolution {
        if (!isNodeJs) return Resolution.Failed(host, ResolveFailure.Unsupported)
        return suspendCancellableCoroutine { cont ->
            lookupAll(host) { error, records ->
                if (cont.isCompleted) return@lookupAll
                if (error != null) {
                    cont.resume(Resolution.Failed(host, failureOf(error.code as? String)))
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

/** Node's `dns` error codes: ENOTFOUND and ENODATA say the name has no address; the rest are failures of the lookup. */
private fun failureOf(code: String?): ResolveFailure =
    when (code) {
        "ENOTFOUND", "ENODATA" -> ResolveFailure.NotFound
        "ETIMEOUT", "ETIMEDOUT", "EAI_AGAIN" -> ResolveFailure.TimedOut
        "ECONNREFUSED", "ENETUNREACH" -> ResolveFailure.NoNetwork
        else -> ResolveFailure.Os(-1)
    }

@Suppress("UNUSED_PARAMETER")
private fun lookupAll(
    host: String,
    callback: (dynamic, dynamic) -> Unit,
) {
    js("require('dns').lookup(host, { all: true, verbatim: true }, callback)")
}
