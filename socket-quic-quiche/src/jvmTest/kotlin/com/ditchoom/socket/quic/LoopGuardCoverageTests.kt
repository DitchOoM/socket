package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GuardedQuicheApi] checks every [QuicheApi] call that takes a connection, `recv_info` or `send_info`. A
 * method added to the interface and not to the decorator would fall through `by delegate` unchecked, so
 * this lists the interface's methods over those handles and requires each to be overridden.
 *
 * The handles are inline value classes, so on the JVM their parameters are `long`s and the methods have
 * mangled names; the list is therefore by name, which follows the interface's own convention: `conn*`
 * (except `connect`, which makes one), `recvInfoFree`, `sendInfo*` (except `sendInfoNew`) and
 * `hasReadableDgram`.
 */
class LoopGuardCoverageTests {
    private fun String.unmangled() = substringBefore('-')

    private fun overConnection(name: String): Boolean =
        (name.startsWith("conn") && name != "connect") ||
            name == "recvInfoFree" ||
            (name.startsWith("sendInfo") && name != "sendInfoNew") ||
            name == "hasReadableDgram"

    @Test
    fun everyQuicheCallOverAConnectionIsGuarded() {
        val needed =
            QuicheApi::class.java.methods
                .map { it.name.unmangled() }
                .filter(::overConnection)
                .toSortedSet()
        assertTrue(needed.size >= 55, "the interface scan found only ${needed.size} connection methods: the naming rule drifted")
        val guarded =
            GuardedQuicheApi::class.java.declaredMethods
                .map { it.name.unmangled() }
                .toSet()
        assertEquals(
            emptyList(),
            needed.filterNot { it in guarded },
            "QuicheApi methods over a connection that GuardedQuicheApi does not check",
        )
    }
}
