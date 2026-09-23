package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Read the peer's leaf certificate DER into [der] (capacity [capacity]) through [driver], so the
 * `quiche_conn_peer_cert` read is serialized with every other access to the connection. Snprintf-style
 * return: see [QuicheCmd.PeerCert].
 *
 * **This does not return while the driver is still borrowing [der].** The caller frees that memory as
 * soon as this returns — `verifyServerCertificateHashes` does it in a `finally` — and the driver has
 * only been handed the buffer's raw address, so returning early on a cancellation leaves quiche writing
 * the certificate into memory that has been handed back to the allocator. That is the same write-after-
 * free shape as #366/#401/#415, reached by a different door: a cancel rather than a dropped reference.
 * `streamRead`, `streamWrite` and the datagram adapter all end with the same non-cancellable join for
 * the same reason; this read is the one command site that did not.
 *
 * The join cannot hang: the driver always completes the deferred — in `execute` after the FFI call, in
 * `cleanup`'s teardown drain, and in `failCommand` — and none of those paths dereferences the memory.
 *
 * One implementation rather than three: a barrier that a backend can forget is a backend that crashes.
 */
internal suspend fun readPeerCertDerThroughDriver(
    driver: QuicheDriver,
    der: PlatformBuffer,
    capacity: Int,
): Int {
    val deferred = CompletableDeferred<Int>()
    // The buffer travels with its address: quiche writes the DER into it on the driver loop, so what
    // keeps that memory mapped has to reach the driver too. See [QuicheMemory].
    driver.commands.send(QuicheCmd.PeerCert(der.driverOwnedMemory(), capacity, deferred))
    // Past the send, so the driver is borrowing: from here every exit waits for it to say it is done.
    try {
        return deferred.await()
    } finally {
        withContext(NonCancellable) { deferred.join() }
    }
}
