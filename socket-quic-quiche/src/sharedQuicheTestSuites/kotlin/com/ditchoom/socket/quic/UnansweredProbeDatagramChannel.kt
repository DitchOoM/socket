@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlin.concurrent.Volatile

/**
 * A server-side [AddressedDatagramChannel] decorator that can make **every migration probe go
 * unanswered**, which is the one network condition a loopback test cannot otherwise produce.
 *
 * An active migration binds a fresh local UDP port and sends its PATH_CHALLENGE from there, so on
 * loopback the server always answers and validation always succeeds. Real handoffs are not like
 * that: on cellular a probe from a new source is routinely dropped outright, the challenge is never
 * answered, and the client's RFC 9000 §8.2.4 timer is what ends the migration. That path is where
 * #447 lives — quiche linked a spare destination CID to the probed path and nothing hands it back —
 * so a guard for it has to be able to produce silence on demand.
 *
 * [dropSourcesNotYetSeen] snapshots the peer addresses seen so far and drops everything from any
 * other source; [allowEverySource] lifts it. Dropping (rather than withholding, as
 * [HoldbackDatagramChannel] does) is the right model here: the datagram is not late, it is gone.
 *
 * Wired in via [QuicPortBinding.Shared], the same production seam a demultiplexed port uses, so
 * nothing about the server under test is test-only.
 *
 * Single-writer by construction: only the server's reader coroutine calls [receive], so the
 * `@Volatile` fields need no atomics — the test coroutine only flips the policy and reads counters.
 */
internal class UnansweredProbeDatagramChannel(
    private val delegate: AddressedDatagramChannel,
) : AddressedDatagramChannel by delegate {
    /**
     * Which sources reach the server. A type rather than a nullable allow-list, because "no policy
     * installed yet" and "an allow-list that happens to be empty" are opposite instructions and a
     * `null` would make them share a token.
     */
    private sealed interface SourcePolicy {
        /** Everything through — the handshake, and everything after [allowEverySource]. */
        data object AllowAll : SourcePolicy

        /** Only [sources] reach the server; every other datagram is dropped as the network drops it. */
        class AllowOnly(
            val sources: Set<SocketAddress>,
        ) : SourcePolicy
    }

    @Volatile
    private var policy: SourcePolicy = SourcePolicy.AllowAll

    /**
     * Every distinct peer that has reached the server. [SocketAddress] has value semantics (its own
     * KDoc requires it be usable as a routing-table key), so a probe's fresh ephemeral port is a
     * distinct element here even though the host is identical.
     */
    private val seen = mutableSetOf<SocketAddress>()

    /** Datagrams dropped so far — the anti-vacuity counter: zero means the block never blocked. */
    @Volatile
    var dropped = 0
        private set

    /**
     * Drop every datagram from a source the server has not already heard from. Call after the
     * connection is established, so the client's original 4-tuple is in [seen] and keeps working
     * while every probe from a new local port dies.
     */
    fun dropSourcesNotYetSeen() {
        policy = SourcePolicy.AllowOnly(seen.toSet())
    }

    /** Lift the block: the next probe's PATH_CHALLENGE reaches the server and is answered. */
    fun allowEverySource() {
        policy = SourcePolicy.AllowAll
    }

    override suspend fun receive(): DatagramReadResult {
        while (true) {
            val result = delegate.receive()
            if (result !is DatagramReadResult.Received) return result
            val peer = result.datagram.peer
            when (val current = policy) {
                SourcePolicy.AllowAll -> {
                    seen += peer
                    return result
                }

                is SourcePolicy.AllowOnly ->
                    if (peer in current.sources) {
                        return result
                    } else {
                        // Nothing else owns this payload — the server never received it — so a drop
                        // that forgot to free it would be exactly the accumulated echo leak that
                        // primed the #401 corruption.
                        result.datagram.payload.freeNativeMemory()
                        dropped++
                    }
            }
        }
    }
}
