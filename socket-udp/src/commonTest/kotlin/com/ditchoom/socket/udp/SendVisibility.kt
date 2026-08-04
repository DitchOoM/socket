package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A send either delivers or reports. It never returns normally having sent nothing.**
 *
 * The invariant this module had no test for, and consequently broke on four of five backends: JVM/NIO
 * discarded `channel.write`/`send`'s return (a non-blocking channel returns 0 when the output buffer is
 * full), Apple's POSIX path discarded `sendto`'s, Apple's NW path resumed unconditionally on the send
 * completion, and Linux discarded the io_uring CQE `res`. Only Node surfaced anything.
 *
 * Why it matters is a consumer fact, not an aesthetic one: `QuicheDriver.flushOutgoing` feeds quiche's
 * congestion controller on the assumption that a returned send means a transmitted packet. A silent
 * drop makes bytes-in-flight and the congestion window count packets that never existed, and the lie is
 * only discovered later as spurious loss detection.
 *
 * ## Both refinements, deliberately
 *
 * Every case runs against the **addressed** channel (`bind`) and the **connected** channel (`connect`),
 * because they are different code on every platform and on Apple they are different *stacks*: `bind`
 * is POSIX `sendto` while `connect` is Network.framework. Covering only `bind` — as the first version
 * of this file did — leaves `NwUdpDatagramChannel` and `ConnectedNioDatagramChannel` untested.
 *
 * ## Hermetic and deterministic
 *
 * Loopback only, no external network, no impairment, no privileges, fixed payload sizes — the same
 * sizes on every run. The wait is a bound, not a race: the deferred completes the instant the datagram
 * lands, so the green path never approaches the timeout and only a genuine non-delivery spends it.
 *
 * Sizes are relative to the sink's own advertised ceiling, so this stays fix-agnostic: whether a
 * backend is fixed by raising `SO_SNDBUF` to honor `maxWritableSize` or by lowering `maxWritableSize`
 * to the truth, "everything you advertise, you can actually send" holds either way.
 *
 * ## Differential against the host
 *
 * A size is only asserted when [host] — a bare socket, no library involved — can carry it over loopback
 * on this machine. See [HostLoopback] for why that is not paranoia: WSL2 silently drops every loopback
 * datagram from 1473 bytes up while presenting a healthy kernel's MTU and sysctls, which is
 * indistinguishable from this suite's target bug when viewed from the sending side. Skipping is
 * narrowly scoped and announced — a host that carries the size still holds the library to it, so a real
 * regression fails everywhere it can be observed.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertSendNeverSilentlyDrops(
    scope: CoroutineScope,
    host: HostLoopback,
) {
    val loopback = MeasuredHostLoopback(host)
    // A probe that cannot carry one byte is a broken probe, not a limited host, and would silently
    // reduce this test to nothing. Fail loudly rather than skip everything.
    if (!loopback.carries(1)) {
        fail("the raw-socket host probe could not carry a 1-byte loopback datagram — the probe is broken, not the host")
    }
    val skipped = mutableListOf<String>()
    for (mode in SendMode.entries) {
        val ceiling = ceilingFor(mode)
        for (size in listOf(1, 1200, 9000, ceiling)) {
            if (!loopback.carries(size)) {
                skipped += "$mode/$size"
                continue
            }
            val outcome = probeSend(scope, size, mode)
            // Every size here is within the sink's own advertised ceiling, so delivery is the only
            // correct outcome — "report an error" is not an escape hatch for a size the API says it
            // supports. Asserting delivery is what stops "throw unconditionally" from passing.
            when {
                outcome.reported == null && outcome.delivered == null ->
                    fail(
                        "[$mode] send of $size bytes (maxWritableSize=$ceiling) returned normally but " +
                            "nothing was delivered — a silent drop. A send must either deliver or report.",
                    )
                outcome.reported != null ->
                    fail(
                        "[$mode] send of $size bytes failed with ${outcome.reported}, but $size is within " +
                            "the advertised maxWritableSize=$ceiling — everything advertised must be sendable.",
                    )
                outcome.delivered != size ->
                    fail("[$mode] send of $size bytes delivered ${outcome.delivered} bytes")
            }
        }
    }
    if (skipped.isNotEmpty()) {
        // Loud on purpose: a skip that reads as a pass is how a suite quietly stops covering anything.
        println(
            "[SendVisibility] SKIPPED $skipped — a plain socket cannot carry those sizes over this host's " +
                "loopback either (largest size carried: ${loopback.largestCarried}). Host limit, not a library " +
                "result. Every size this host can carry was asserted.",
        )
    }
}

/**
 * [HostLoopback] measured once per size and remembered, so the two refinements share one answer per
 * size and the report can name the largest size this host was actually seen to carry.
 */
private class MeasuredHostLoopback(
    private val host: HostLoopback,
) {
    private val answers = mutableMapOf<Int, Boolean>()

    var largestCarried: Int = 0
        private set

    suspend fun carries(size: Int): Boolean =
        answers.getOrPut(size) {
            host.carries(size).also { if (it && size > largestCarried) largestCarried = size }
        }
}

/**
 * The complement: a payload **beyond** the advertised ceiling must report, and must report the *same*
 * typed reason on every platform.
 *
 * Parity is the point, not merely "it throws". A consumer branching on the error — ICE marking a
 * candidate pair unusable on [DatagramSendError.TooLarge] — is only able to do so if every backend
 * agrees on the reason. The backends derive their errors from different sources (`errno`, a
 * Network.framework `(domain, code)`, a Node error `code`, a JVM `IOException` that carries no errno at
 * all), so without an explicit contract they would each report something different for the same
 * condition, and the typed error would be barely better than an untyped one.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertOversizedSendReportsTooLarge(scope: CoroutineScope) {
    for (mode in SendMode.entries) {
        val ceiling = ceilingFor(mode)
        val outcome = probeSend(scope, ceiling + 1, mode)
        val reported = outcome.reported ?: fail("[$mode] a payload of ${ceiling + 1} bytes must not be accepted silently")
        val error =
            (reported as? DatagramSendException)?.error
                ?: fail("[$mode] expected a DatagramSendException, got $reported")
        assertTrue(
            error is DatagramSendError.TooLarge,
            "[$mode] a payload past maxWritableSize=$ceiling must report TooLarge on every platform, got $error",
        )
        assertTrue(outcome.delivered == null, "[$mode] an oversized payload must not be delivered")
    }
}

/** Which refinement a probe drives. They are separate implementations on every platform. */
internal enum class SendMode { Addressed, Connected }

/**
 * The advertised ceiling **of the refinement under test**, read from a real channel of that mode.
 *
 * Deliberately not one number for the module: on Apple the two refinements are different stacks with
 * genuinely different limits — `bind` is a POSIX socket whose `SO_SNDBUF` this module widens to the
 * protocol ceiling, while `connect` is Network.framework, which exposes no socket to widen and is held
 * to Darwin's default UDP datagram size. Each channel advertises its own truth, so the contract is
 * checked against the truth the caller would actually read.
 */
@OptIn(ExperimentalDatagramApi::class)
private suspend fun ceilingFor(mode: SendMode): Int =
    when (mode) {
        SendMode.Addressed -> UdpSocket.bind("127.0.0.1", 0).let { it.maxWritableSize.also { _ -> it.close() } }
        SendMode.Connected -> {
            val receiver = UdpSocket.bind("127.0.0.1", 0)
            val sender = UdpSocket.connect("127.0.0.1", receiver.localAddress.port)
            sender.maxWritableSize.also {
                sender.close()
                receiver.close()
            }
        }
    }

private class SendOutcomeProbe(
    val reported: Throwable?,
    val delivered: Int?,
)

/**
 * Send [size] bytes over a fresh loopback pair and report both halves of the outcome: what the send
 * itself said, and what actually arrived.
 *
 * A fresh pair per probe because closing the receiver is the only way to release a parked native
 * `recvfrom` — a cancelled receive does not unblock it on the Apple POSIX path. The sender is closed
 * only after delivery has been observed, so a close can never race an in-flight send.
 */
@OptIn(ExperimentalDatagramApi::class)
private suspend fun probeSend(
    scope: CoroutineScope,
    size: Int,
    mode: SendMode,
): SendOutcomeProbe {
    val receiver = UdpSocket.bind("127.0.0.1", 0)
    val landed = CompletableDeferred<Int>()
    // runCatching inside the coroutine: closing a channel with a parked receive is not uniformly
    // clean across backends (the JVM selector path can surface ClosedSelectorException out of a parked
    // select rather than returning Closed), and this probe must report on the *send*, not fail on how
    // teardown happens to unwind.
    val reader =
        scope.launch(Dispatchers.Default) {
            runCatching {
                val r = receiver.receive()
                if (r is DatagramReadResult.Received) landed.complete(r.datagram.payload.remaining())
            }
        }

    val payload = filled(size)
    val reported: Throwable?
    val sender: AutoCloseableChannel =
        when (mode) {
            SendMode.Addressed -> {
                val s = UdpSocket.bind("127.0.0.1", 0)
                reported = runCatching { s.send(payload, to = receiver.localAddress) }.exceptionOrNull()
                AutoCloseableChannel { s.close() }
            }
            SendMode.Connected -> {
                val s = UdpSocket.connect("127.0.0.1", receiver.localAddress.port)
                reported = runCatching { s.send(payload) }.exceptionOrNull()
                AutoCloseableChannel { s.close() }
            }
        }

    // Wait either way. When the send reported, the wait is what proves nothing was *also* delivered —
    // shorter, because the expectation is silence rather than arrival.
    val delivered = withTimeoutOrNull(if (reported == null) 2_000 else 300) { landed.await() }

    sender.close()
    receiver.close() // releases the parked native recvfrom
    reader.cancel()
    return SendOutcomeProbe(reported, delivered)
}

private fun filled(size: Int): ReadBuffer {
    val payload = PlatformBuffer.allocateNative(size)
    repeat(size) { payload.writeByte(0x41) }
    payload.resetForRead()
    return payload
}

/** Tiny shim so both refinements — which share no common closable supertype here — close uniformly. */
private class AutoCloseableChannel(
    private val closer: () -> Unit,
) {
    fun close() = closer()
}

/**
 * **Closing a channel with a parked receive yields [DatagramReadResult.Closed] — it does not throw.**
 *
 * The contract every backend's KDoc states ("once closed, `receive` yields Closed") and which the JVM
 * path broke: `close()` sets the flag, wakes the selector and then closes it, so a receive parked in
 * `select()` — or caught between `select()` returning and reading `selectedKeys()` — found the selector
 * already gone and surfaced `ClosedSelectorException`. A caller draining in a loop would take a
 * spurious failure purely from shutdown ordering, which is exactly the shape of bug that looks like a
 * flaky test rather than a real defect.
 *
 * Found while writing the oversize send probe, whose teardown happens to close a receiver that never
 * received anything — the ordinary case for a send that correctly refused to transmit.
 *
 * Deterministic without being timing-dependent on the *outcome*: the delay only ensures the receive has
 * reached its parked state before the close, and the assertion is on what `receive` returns, not on how
 * long anything took.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertCloseWithParkedReceiveYieldsClosed(scope: CoroutineScope) {
    val channel = UdpSocket.bind("127.0.0.1", 0)
    val outcome = CompletableDeferred<Result<DatagramReadResult>>()
    scope.launch(Dispatchers.Default) { outcome.complete(runCatching { channel.receive() }) }

    kotlinx.coroutines.delay(250) // let the receive park; the assertion below is not timing-dependent
    channel.close()

    val result =
        withTimeoutOrNull(5_000) { outcome.await() }
            ?: fail("closing a channel with a parked receive must release it, but receive never returned")
    result.exceptionOrNull()?.let { fail("a parked receive must yield Closed on close, but threw $it") }
    val value = result.getOrThrow()
    assertTrue(
        value is DatagramReadResult.Closed,
        "a parked receive must yield Closed on close, got $value",
    )
}
