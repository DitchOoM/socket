package com.ditchoom.socket

/**
 * Classifies errors as recoverable (retry after delay) or non-recoverable (give up).
 *
 * `suspend` enables network-aware reconnection (e.g., waiting for connectivity change).
 * `fun interface` enables SAM lambda: `ReconnectionClassifier { error -> ... }`
 *
 * Takes [Throwable] rather than [SocketException] so protocol libraries can feed
 * their own domain-specific exception types.
 */
fun interface ReconnectionClassifier {
    suspend fun classify(error: Throwable): ReconnectDecision
}
