package com.ditchoom.socket.http3

/**
 * Run [block]; if it throws, print a one-line `DIFF-DEBUG` snapshot of the failing case's inputs
 * ([label] + [context]) to stdout, then rethrow the original throwable unchanged.
 *
 * Generalizes the capture pattern that started in `QpackDifferentialInteropTests` (print the wire bytes
 * that made the ls-qpack oracle disagree, then rethrow): a fuzz / differential failure self-reports the
 * exact repro material — the seed, the wire hex, the case index — instead of surfacing only a class name
 * + message with no way to reconstruct the input. `DIFF-DEBUG` is kept as the literal marker so a single
 * grep across CI logs finds every captured repro regardless of which suite produced it.
 *
 * [context] is evaluated **only on failure**, so building an expensive hex/repro string costs nothing on
 * the (overwhelmingly common) success path. `inline` so the lambdas may suspend when the caller does.
 */
internal inline fun <T> withDiffDebug(
    label: String,
    context: () -> String,
    block: () -> T,
): T =
    try {
        block()
    } catch (t: Throwable) {
        println("DIFF-DEBUG $label ${context()}")
        throw t
    }
