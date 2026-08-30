@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.udp.nw.socket_mc_set_ttl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_UDP
import platform.posix.IP_MULTICAST_TTL
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockopt
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The multicast control plane is admitted like every other user of the descriptor (#527).
 *
 * `MulticastPosixUdpDatagramChannel` delegates its data plane to a [PosixUdpDatagramChannel] and used to
 * hold that channel's descriptor *number* as well, `setsockopt`ing it directly. The number's owner —
 * [LastOutHandoff] — never knew: it admits `receive`, `send` and `close`, and releases the descriptor when
 * the last of *those* leaves. So a `joinGroup`/`setTimeToLive` in flight was invisible, `close()` could see
 * an empty channel and release the descriptor, and the next `socket()` in the process took the number.
 *
 * [controlCallNotYetAdmittedWhenCloseRuns_isRefusedAndNeverTouchesTheRecycledDescriptor] measures exactly
 * that theft rather than arguing it, and — per #507's lesson — measures it **from the victim**: a
 * caller-side assertion alone passes happily while a stranger's socket is being rewritten, because a
 * `setsockopt` on a live descriptor *succeeds*. So the fresh socket that takes the recycled number has its
 * own multicast TTL set first, and must still be reading it back afterwards.
 *
 * [controlCallAdmittedBeforeClose_holdsTheDescriptorOpenUntilItLeaves] is the other half of the same
 * property: a control op that *was* admitted before `close()` keeps the descriptor alive under itself,
 * completes against its own socket, and releases everything on its way out as the last party.
 */
@OptIn(ExperimentalDatagramApi::class)
class MulticastControlPlaneAdmissionTests {
    @Test
    fun controlCallNotYetAdmittedWhenCloseRuns_isRefusedAndNeverTouchesTheRecycledDescriptor() {
        runBlocking {
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Parked on entry to the control op, before admission: the caller is inside setTimeToLive and
            // close() has not run yet — the whole window the unadmitted control plane lived in.
            val bound =
                boundLoopbackMulticastChannel(
                    beforeAdmission = {
                        parked.complete(Unit)
                        release.await()
                    },
                )
            val fresh = ArrayList<Int>()
            try {
                val control = async(Dispatchers.Default) { runCatching { bound.channel.setTimeToLive(ATTEMPTED_TTL) } }
                withTimeout(WAIT) { parked.await() }
                bound.channel.close()
                assertFalse(bound.channel.isOpen, "close() must refuse every later party")
                // Nobody was admitted, so close() was genuinely last out and released the descriptor: its
                // number is free, and socket() hands out the lowest free one.
                repeat(FRESH_SOCKETS) { fresh += openDatagramSocket() }
                val victim =
                    fresh.firstOrNull { it == bound.fd }
                        ?: fail(
                            "vacuous run: the channel's fd ${bound.fd} was not recycled by any of $FRESH_SOCKETS fresh " +
                                "sockets $fresh, so there is no victim to rob — the witness proves nothing",
                        )
                setMulticastTtl(victim, VICTIM_TTL)
                assertEquals(VICTIM_TTL, multicastTtl(victim), "the victim socket must start at its own TTL")
                release.complete(Unit)
                val outcome = withTimeout(WAIT) { control.await() }
                // The victim first, deliberately (#507's lesson): unfixed, the caller sees a clean return and
                // only this socket can say what happened to it, so this is the assertion that names the theft.
                assertEquals(
                    VICTIM_TTL,
                    multicastTtl(victim),
                    "fresh socket fd=$victim (the channel's recycled number ${bound.fd}) had its multicast TTL " +
                        "rewritten by setTimeToLive($ATTEMPTED_TTL) on a channel that was already closed; that call " +
                        "reported ${describe(outcome)}",
                )
                assertIs<MulticastException.ChannelClosed>(
                    outcome.exceptionOrNull(),
                    "a control call racing close() is owed ChannelClosed; channel fd=${bound.fd} was recycled as the " +
                        "fresh socket fd=$victim, and setTimeToLive($ATTEMPTED_TTL) reported ${describe(outcome)}",
                )
            } finally {
                fresh.forEach { close(it) }
                bound.channel.close()
            }
        }
    }

    @Test
    fun controlCallAdmittedBeforeClose_holdsTheDescriptorOpenUntilItLeaves() =
        runBlocking {
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Parked after admission this time: the descriptor is borrowed, so nothing may release it.
            val bound =
                boundLoopbackMulticastChannel(
                    beforeSyscall = {
                        parked.complete(Unit)
                        release.await()
                    },
                )
            try {
                val control = async(Dispatchers.Default) { runCatching { bound.channel.setTimeToLive(ATTEMPTED_TTL) } }
                withTimeout(WAIT) { parked.await() }
                bound.channel.close()
                assertFalse(bound.channel.isOpen, "close() must refuse every later party")
                assertTrue(
                    isOpenDescriptor(bound.fd),
                    "close() must not release fd ${bound.fd} while an admitted control call is standing on it",
                )
                release.complete(Unit)
                val outcome = withTimeout(WAIT) { control.await() }
                // A `setsockopt` that returns 0 is itself proof the descriptor was live — and it was this
                // channel's, because nothing here ever recycled the number.
                assertTrue(
                    outcome.isSuccess,
                    "an admitted control call applies to its own socket, closed channel or not: ${describe(outcome)}",
                )
                // Released by the control call, not by close(): it was the last party out.
                assertFalse(isOpenDescriptor(bound.fd), "the control call, last out, must release fd ${bound.fd}")
                assertRecvDispatcherIsClosed(bound.base)
            } finally {
                bound.channel.close()
            }
        }

    private fun describe(outcome: Result<Unit>): String =
        outcome.fold(
            onSuccess = { "a clean return (the setsockopt ran)" },
            onFailure = { "${it::class.simpleName}: ${it.message}" },
        )

    /** An unbound IPv4 datagram socket — enough to carry `IP_MULTICAST_TTL`, and it takes the lowest free fd. */
    private fun openDatagramSocket(): Int {
        val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        check(fd >= 0) { "socket(AF_INET, SOCK_DGRAM) failed: errno $errno" }
        return fd
    }

    /** Through the production shim, so the victim is written by the very call the thief would have made. */
    private fun setMulticastTtl(
        fd: Int,
        ttl: Int,
    ) {
        check(socket_mc_set_ttl(fd, 0, ttl) == 0) { "socket_mc_set_ttl($fd, $ttl) failed: errno $errno" }
    }

    /**
     * `IP_MULTICAST_TTL` read back from [fd]. Darwin answers this one as a `u_char`, so the reply is taken
     * as its own reported length (little-endian) rather than assumed to be an `int`.
     */
    private fun multicastTtl(fd: Int): Int =
        memScoped {
            val raw = allocArray<ByteVar>(OPTION_BYTES)
            for (i in 0 until OPTION_BYTES) raw[i] = 0
            val length = alloc<UIntVar>()
            length.value = OPTION_BYTES.convert()
            check(getsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, raw, length.ptr) == 0) {
                "getsockopt(IP_MULTICAST_TTL) on fd $fd failed: errno $errno"
            }
            var value = 0
            for (i in 0 until length.value.toInt()) value = value or ((raw[i].toInt() and 0xFF) shl (8 * i))
            value
        }

    private companion object {
        val WAIT = 10.seconds

        /** What the racing control call tries to apply — and must not, to anyone. */
        const val ATTEMPTED_TTL = 200

        /** What the victim socket holds before the parked call is released, and must still hold after. */
        const val VICTIM_TTL = 7

        const val FRESH_SOCKETS = 6
        const val OPTION_BYTES = 4
    }
}
