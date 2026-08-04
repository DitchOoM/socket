package com.ditchoom.socket.udp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.khronos.webgl.Uint8Array

/**
 * The Node [HostLoopback]: a bare `dgram` socket pair, bypassing `NodeDatagramChannel` entirely — the
 * only thing borrowed from production is [createDgramSocket], whose `sendBufferSize: 65507` is exactly
 * the widening [HostLoopback] requires the probe to match.
 */
internal val hostLoopback = HostLoopback { size -> rawLoopbackCarries(size) }

private suspend fun rawLoopbackCarries(size: Int): Boolean {
    val receiver = createDgramSocket("udp4")
    val sender = createDgramSocket("udp4")
    try {
        val landed = CompletableDeferred<Int>()
        val bound = CompletableDeferred<Int>()
        receiver.on("message") { message: Uint8Array, _: RInfo -> landed.complete(message.length) }
        receiver.bind(0, "127.0.0.1") { bound.complete(receiver.address().port) }
        val port = bound.await()

        val accepted = CompletableDeferred<Boolean>()
        sender.send(Uint8Array(size), 0, size, port, "127.0.0.1") { error -> accepted.complete(error == null) }
        // A rejected send (EMSGSIZE) is a host refusal; an accepted send that never lands is a host drop.
        return accepted.await() && withTimeoutOrNull(RECEIVE_TIMEOUT_MILLIS) { landed.await() } == size
    } finally {
        sender.removeAllListeners()
        receiver.removeAllListeners()
        sender.close {}
        receiver.close {}
    }
}

private const val RECEIVE_TIMEOUT_MILLIS = 1_000L
