package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.withContext

/**
 * Read the peer's certificate at [position] (the leaf by default, or one position in its chain) as DER
 * into [der] (capacity [capacity]) through [driver], so the quiche read is serialized with every other
 * access to the connection. Snprintf-style return: see [QuicheCmd.PeerCert]; 0 past the end of the chain.
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
    position: PeerCertPosition = PeerCertPosition.Leaf,
): Int {
    val deferred = CompletableDeferred<Int>()
    // The buffer travels with its address: quiche writes the DER into it on the driver loop, so what
    // keeps that memory mapped has to reach the driver too. See [QuicheMemory].
    try {
        driver.commands.send(QuicheCmd.PeerCert(der.driverOwnedMemory(), capacity, deferred, position))
    } catch (_: ClosedSendChannelException) {
        // The connection is gone (a peer may close the instant its handshake completes): answer from the
        // chain the driver copied out as it tore down, so the certificate is still checked, not refused.
        return driver.copyRetainedPeerCert(position, der, capacity)
    }
    // Past the send, so the driver is borrowing: from here every exit waits for it to say it is done.
    val read =
        try {
            deferred.await()
        } finally {
            withContext(NonCancellable) { deferred.join() }
        }
    // A read the teardown drain answered "nothing here" for: the retained chain, as above. A live
    // connection's 0 (no certificate) stands.
    return if (read == 0 && driver.commands.isClosedForSend) driver.copyRetainedPeerCert(position, der, capacity) else read
}
