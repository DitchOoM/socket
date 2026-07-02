package com.ditchoom.socket

import com.ditchoom.socket.transport.TransportFailure

/**
 * Every transport in a fallback chain failed to connect (RFC_TRANSPORT_FALLBACK §3). A member of the
 * [SocketException] family so `catch (e: SocketException)` (or `IOException` on JVM) handles it
 * uniformly with any other connect failure — the fallback layer never leaks a foreign exception type.
 *
 * [failures] retains each rung's [TransportFailure] (transport, error, verdict) for diagnostics, in
 * attempt order; [cause] is the last rung's error.
 */
class TransportsExhausted(
    val host: String,
    val port: Int,
    val failures: List<TransportFailure>,
) : SocketException(
        buildMessage(host, port, failures),
        cause = failures.lastOrNull()?.error,
    )

private fun buildMessage(
    host: String,
    port: Int,
    failures: List<TransportFailure>,
): String =
    "All ${failures.size} transport(s) failed connecting to $host:$port" +
        failures.joinToString(prefix = " [", postfix = "]") { failure ->
            "${failure.transport}: ${failure.error::class.simpleName}(${failure.error.message})"
        }
