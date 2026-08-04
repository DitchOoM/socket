package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail

/**
 * **A send either delivers or reports. It never returns normally having sent nothing.**
 *
 * The invariant this module had no test for, and consequently broke on four of five backends: JVM/NIO
 * discarded `channel.write`/`send`'s return (a non-blocking channel returns 0 when the output buffer is
 * full), Apple's POSIX path discarded `sendto`'s, Apple's NW path resumed unconditionally on the send
 * completion, and Linux discarded the io_uring CQE `res`. Only Node surfaced anything. The observable
 * result on Apple was `send()` returning cleanly while nothing left the host — measured at 20000 and
 * 65507 bytes against an advertised [maxWritableSize] of 65507.
 *
 * Why it matters is a consumer fact, not an aesthetic one: `QuicheDriver.flushOutgoing` feeds quiche's
 * congestion controller on the assumption that a returned send means a transmitted packet. A silent
 * drop makes bytes-in-flight and the congestion window count packets that never existed, and the lie is
 * only discovered later as spurious loss detection.
 *
 * ## Hermetic and deterministic
 *
 * Loopback only, no external network, no impairment, fixed payload sizes — the same sizes on every run.
 * The wait is a bound, not a race: the deferred completes the instant the datagram lands, so the green
 * path never approaches the timeout and only a genuine non-delivery spends it.
 *
 * Sizes are chosen relative to the sink's own advertised ceiling, so this stays fix-agnostic: whether
 * the backend is fixed by raising `SO_SNDBUF` to honor [maxWritableSize] or by lowering
 * [maxWritableSize] to the truth, "everything you advertise, you can actually send" holds either way.
 *
 * Each size gets a fresh socket pair because closing the receiver is the only way to release a parked
 * native `recvfrom` — a cancelled receive does not unblock it on the Apple POSIX path.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertSendNeverSilentlyDrops(scope: CoroutineScope) {
    val ceiling = UdpSocket.bind("127.0.0.1", 0).use { it.maxWritableSize }
    for (size in listOf(1, 1200, 9000, ceiling)) {
        val a = UdpSocket.bind("127.0.0.1", 0)
        val b = UdpSocket.bind("127.0.0.1", 0)

        val landed = CompletableDeferred<Int>()
        val reader =
            scope.launch(Dispatchers.Default) {
                val r = b.receive()
                if (r is DatagramReadResult.Received) landed.complete(r.datagram.payload.remaining())
            }

        val payload = PlatformBuffer.allocateNative(size)
        repeat(size) { payload.writeByte(0x41) }
        payload.resetForRead()

        val reported = runCatching { a.send(payload, to = b.localAddress) }.exceptionOrNull()
        val delivered = if (reported == null) withTimeoutOrNull(2_000) { landed.await() } else null

        b.close() // releases the parked native recvfrom
        a.close()
        reader.cancel()

        if (reported == null && delivered == null) {
            fail(
                "send of $size bytes (maxWritableSize=$ceiling) returned normally but nothing was " +
                    "delivered — a silent drop. A send must either deliver or report.",
            )
        }
        if (delivered != null && delivered != size) {
            fail("send of $size bytes delivered $delivered bytes")
        }
    }
}

@OptIn(ExperimentalDatagramApi::class)
private inline fun <T : com.ditchoom.buffer.flow.DatagramChannel, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
