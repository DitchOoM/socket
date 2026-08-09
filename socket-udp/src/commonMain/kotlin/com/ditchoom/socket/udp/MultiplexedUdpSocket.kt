@file:OptIn(ExperimentalDatagramApi::class, DelicateCoroutinesApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Datagrams buffered per branch before the oldest is dropped (see [MultiplexedDropCounts]). */
private const val DEFAULT_BRANCH_CAPACITY = 64

/**
 * A datagram delivered to the shared-port consumer, with the protocol it was classified as.
 *
 * [protocol] is a [MultiplexedProtocol.NonQuic], so a `when` over it is exhaustive across exactly
 * what can arrive here: QUIC packets went to [MultiplexedUdpSocket.quic] and unroutable ones were
 * dropped at the door, and neither is a case this consumer can be handed.
 *
 * Ownership of `datagram.payload` transfers to the collector, which frees it (a pooled payload
 * returns to its pool) — the same allocate-and-transfer rule as a raw [AddressedDatagramChannel].
 */
class MultiplexedDatagram internal constructor(
    val protocol: MultiplexedProtocol.NonQuic,
    val datagram: Datagram,
)

/**
 * Why the demultiplexer dropped datagrams. Counted rather than logged, and split by cause, because
 * the three mean completely different things: [unroutable] is a peer sending junk (or a scan),
 * [quicOverflow] is a QUIC stack not draining, and [nonQuicOverflow] is a media/ICE consumer not
 * draining. A shared port that "went quiet" is diagnosed here.
 */
data class MultiplexedDropCounts(
    /** First byte in RFC 9443's unassigned range, or a zero-length datagram. */
    val unroutable: Long = 0,
    /** QUIC-classified datagrams dropped because the QUIC branch's buffer was full or detached. */
    val quicOverflow: Long = 0,
    /** Non-QUIC datagrams dropped because the consumer's buffer was full or uncollected. */
    val nonQuicOverflow: Long = 0,
)

/**
 * One bound UDP port, demultiplexed per RFC 9443 — the arrangement that lets a QUIC stack (and so
 * HTTP/3 and WebTransport) share port 443 with the WebRTC family (ICE/STUN, DTLS, SRTP, TURN).
 *
 * A single socket cannot have two readers, so this owns the read side and splits it:
 * - [quic] is an [AddressedDatagramChannel] carrying only QUIC-classified datagrams — hand it
 *   straight to the QUIC listener, which then never binds a port of its own; and
 * - [datagrams] carries everything else, already classified, for a sans-I/O media/ICE stack.
 *
 * Both branches send through the same socket ([send] and `quic.send` are serialized against each
 * other), so replies leave from the port the peer is talking to — which is what ICE requires.
 *
 * ## Lifecycle
 * The mux owns the socket: [close] closes it and ends both branches. Closing the [quic] branch
 * detaches *only* QUIC (its receives return `Closed`, its later datagrams are dropped) and leaves
 * the port serving media — a QUIC listener shutting down must not take the call with it. This is why
 * `quic.close()` is not the socket's close, and why the QUIC listener is handed a branch rather than
 * the socket.
 *
 * ## Backpressure
 * Each branch has a bounded buffer and drops the OLDEST datagram when a consumer falls behind,
 * rather than blocking the reader — otherwise one stalled stack would stall the other, turning a
 * slow media consumer into a QUIC outage. Drops are counted in [dropped], never silent.
 */
interface MultiplexedUdpSocket {
    /** The bound local endpoint — the port every branch shares (ICE local-candidate gathering). */
    val localAddress: SocketAddress

    /**
     * The QUIC branch: an [AddressedDatagramChannel] that receives only QUIC-classified datagrams and
     * sends through the shared socket. Its `close()` detaches QUIC from this port without closing it.
     */
    val quic: AddressedDatagramChannel

    /**
     * Everything that is not QUIC, classified. **Single-collector**: the payloads are owned, not
     * copied, so they can only be handed to one consumer — collecting twice throws.
     */
    val datagrams: Flow<MultiplexedDatagram>

    /** Running drop tally by cause. */
    val dropped: StateFlow<MultiplexedDropCounts>

    /** Send [payload] to [to] over the shared socket, serialized against the QUIC branch's sends. */
    suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions = DatagramSendOptions.Default,
    )

    /** Close the socket and end both branches. Idempotent. */
    fun close()
}

/**
 * Demultiplex this bound UDP channel per RFC 9443, splitting QUIC from the ICE/media protocols.
 *
 * Defined over an [AddressedDatagramChannel] rather than over a socket on purpose: the real socket
 * (`UdpSocket.bind(...)`) and an in-memory test channel are both valid inputs, so a consumer can test
 * its shared-port handling — including the QUIC branch — with no sockets, no ports and no timing.
 *
 * ```kotlin
 * val socket = UdpSocket.bind(localPort = 443, bufferFactory = recvPool)
 * val mux = socket.demultiplex(scope, relays = TurnRelays(setOf(turnServer)))
 *
 * launch { mux.datagrams.collect { iceAgent.onDatagram(it.protocol, it.datagram) } }
 * withQuicServer(binding = QuicPortBinding.Shared(mux.quic), tlsConfig = tls, quicOptions = options) {
 *     connectionsByAlpn("h3" to { serveHttp3(...) }, "my-proto" to { ... })
 * }
 * ```
 *
 * [scope] runs the single reader that feeds both branches; cancelling it ends the demux (the socket
 * itself is closed by [MultiplexedUdpSocket.close]). [relays] resolves the one first-byte range TURN
 * and QUIC share — see [TurnRelays]. [branchCapacity] is the per-branch buffer depth before the
 * oldest datagram is dropped.
 */
fun AddressedDatagramChannel.demultiplex(
    scope: CoroutineScope,
    relays: TurnRelays = TurnRelays.None,
    branchCapacity: Int = DEFAULT_BRANCH_CAPACITY,
): MultiplexedUdpSocket = DemultiplexingUdpSocket(this, scope, relays, branchCapacity)

private class DemultiplexingUdpSocket(
    private val channel: AddressedDatagramChannel,
    scope: CoroutineScope,
    private val relays: TurnRelays,
    branchCapacity: Int,
) : MultiplexedUdpSocket {
    init {
        require(branchCapacity > 0) { "branchCapacity must be positive: $branchCapacity" }
    }

    override val localAddress: SocketAddress get() = channel.localAddress

    private val _dropped = MutableStateFlow(MultiplexedDropCounts())
    override val dropped: StateFlow<MultiplexedDropCounts> get() = _dropped

    /**
     * One writer (the reader coroutine), so the counters need no atomics; the StateFlow publishes
     * them safely to any observer.
     */
    private fun countDrop(update: (MultiplexedDropCounts) -> MultiplexedDropCounts) {
        _dropped.value = update(_dropped.value)
    }

    // Bounded, and the drop-oldest is performed by hand in [offer] rather than through
    // BufferOverflow.DROP_OLDEST: that policy evicts the element *without* invoking the
    // undelivered-element hook, so every evicted payload would leak (a pooled chunk never returned)
    // and every drop would go uncounted. The hook stays for the other path it does cover — payloads
    // still buffered when a branch is closed or cancelled.
    private val quicInbox =
        Channel<Datagram>(branchCapacity) { it.payload.freeIfNeeded() }

    private val nonQuicInbox =
        Channel<MultiplexedDatagram>(branchCapacity) { it.datagram.payload.freeIfNeeded() }

    /**
     * buffer-flow does not assume a channel's send is thread-safe ("confine sends to one coroutine"),
     * and a shared port has two independent senders by definition — the QUIC stack's egress and the
     * media stack's. Serializing them here is what makes the sharing legal rather than lucky; the
     * lock is uncontended in the common case and always shorter than the syscall it guards.
     */
    private val sendLock = Mutex()

    override val datagrams: Flow<MultiplexedDatagram> = nonQuicInbox.consumeAsFlow()

    override val quic: AddressedDatagramChannel = QuicBranch()

    /** The single reader of the shared socket — the reason two stacks can coexist on it at all. */
    private suspend fun readLoop() {
        try {
            while (true) {
                when (val result = channel.receive()) {
                    is DatagramReadResult.Closed -> break
                    is DatagramReadResult.Received -> route(result.datagram)
                }
            }
        } finally {
            // Whatever ended the read side — close, cancellation, socket error — both consumers must
            // see it: an unclosed branch would leave a collector suspended forever.
            quicInbox.close()
            nonQuicInbox.close()
        }
    }

    private fun route(datagram: Datagram) {
        when (val protocol = relays.classify(datagram.payload, datagram.peer)) {
            is MultiplexedProtocol.NonQuic ->
                offer(
                    inbox = nonQuicInbox,
                    item = MultiplexedDatagram(protocol, datagram),
                    payloadOf = { it.datagram.payload },
                    count = { c -> c.copy(nonQuicOverflow = c.nonQuicOverflow + 1) },
                )

            MultiplexedProtocol.Quic ->
                offer(
                    inbox = quicInbox,
                    item = datagram,
                    payloadOf = { it.payload },
                    count = { c -> c.copy(quicOverflow = c.quicOverflow + 1) },
                )

            MultiplexedProtocol.Unroutable -> {
                datagram.payload.freeIfNeeded()
                countDrop { it.copy(unroutable = it.unroutable + 1) }
            }
        }
    }

    /**
     * Hand [item] to a branch, making room by dropping its OLDEST datagram if the consumer has fallen
     * behind — never by suspending the shared reader, which is what would let one stack's stall become
     * the other's outage. A closed branch (its stack detached) drops the datagram outright. Every
     * dropped payload is freed here, and every drop is counted.
     */
    private fun <T> offer(
        inbox: Channel<T>,
        item: T,
        payloadOf: (T) -> PlatformBuffer,
        count: (MultiplexedDropCounts) -> MultiplexedDropCounts,
    ) {
        if (inbox.trySend(item).isSuccess) return
        // isClosedForSend is "delicate" because a channel can close between the check and the act;
        // here that race is harmless — either way the datagram is dropped, freed and counted, and the
        // only writer of these branches is this single reader coroutine.
        if (!inbox.isClosedForSend) {
            // Full: evict the oldest by actually receiving it, so the payload is ours to free.
            inbox.tryReceive().getOrNull()?.let { evicted ->
                payloadOf(evicted).freeIfNeeded()
                countDrop(count)
            }
            if (inbox.trySend(item).isSuccess) return
        }
        payloadOf(item).freeIfNeeded()
        countDrop(count)
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) = sendLock.withLock { channel.send(payload, to, options) }

    override fun close() {
        // Closing the socket makes the in-flight receive return Closed, which ends the reader, which
        // closes both branches. Cancelling the reader directly would race that teardown.
        channel.close()
    }

    /**
     * The QUIC branch. Receives from [quicInbox]; sends and capabilities pass straight through to the
     * shared socket, so quiche sees an ordinary bound channel and needs to know nothing about the
     * arrangement. [close] detaches QUIC only — the socket keeps serving the other branch.
     */
    private inner class QuicBranch : AddressedDatagramChannel {
        override val localAddress: SocketAddress get() = channel.localAddress
        override val capabilities: DatagramCapabilities get() = channel.capabilities
        override val maxWritableSize: Int get() = channel.maxWritableSize
        override val isOpen: Boolean get() = !quicInbox.isClosedForSend && channel.isOpen

        override suspend fun receive(): DatagramReadResult =
            quicInbox
                .receiveCatching()
                .getOrNull()
                ?.let { DatagramReadResult.Received(it) }
                ?: DatagramReadResult.Closed()

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) = sendLock.withLock { channel.send(payload, to, options) }

        override fun close() {
            // NOT the socket: the QUIC listener's shutdown must not take the media path down with it.
            quicInbox.close()
        }
    }

    init {
        // Last: every branch and the send lock must exist before the reader can route into them.
        scope.launch { readLoop() }
    }
}
