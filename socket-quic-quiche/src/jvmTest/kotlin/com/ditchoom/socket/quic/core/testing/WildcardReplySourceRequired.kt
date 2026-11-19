package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.core.ready.WildcardReplySource
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.ready.wildcardReplySource
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import org.junit.Assume.assumeTrue
import kotlin.reflect.KClass

/**
 * Skips the calling test, with the skip marker the CI inventory counts, where this runtime's stack lets the kernel pick a
 * wildcard server's reply source: the JDK stack, which Windows runs because `qnet` has no Windows build.
 */
internal fun assumeWildcardRepliesLeaveFromArrival(site: KClass<*>) {
    when (netStackOfThisRuntime.wildcardReplySource) {
        WildcardReplySource.FromArrival -> Unit
        WildcardReplySource.KernelChooses -> {
            recordSkip(
                site,
                SkipReason.HostBehaviourDiffers(
                    "the JDK stack (Windows: qnet has no Windows build) cannot read a datagram's destination or name a " +
                        "reply's source through java.nio, so a wildcard server answers from the address the kernel picks; " +
                        "closing it needs qnet on Windows (WSARecvMsg/WSASendMsg with IP_PKTINFO) or a socket per local address",
                ),
                SkipGate.HostCannotProvideIt("a reply source named per datagram through java.nio"),
            )
            assumeTrue("the JDK stack lets the kernel pick a wildcard server's reply source", false)
        }
    }
}
