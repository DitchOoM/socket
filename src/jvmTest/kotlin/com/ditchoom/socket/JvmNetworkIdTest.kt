package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmNetworkIdTest {
    @Test
    fun currentPrimaryNetworkIdIsAStableLinkOrUnidentified() {
        // Host-independent invariants: the derivation reuses the availability scan to keep the primary
        // interface's OS index as a NetworkId.Link handle (the discriminator QUIC auto-migration reacts
        // to), or Unidentified when nothing qualifies. Never throws, never a bare string/null.
        when (val id = currentPrimaryNetworkId()) {
            is NetworkId.Link -> {
                assertTrue(id.handle != 0L, "a real link must carry a non-zero interface handle")
                val kind = id.kind
                assertTrue(kind is NetworkKind.Other && kind.raw.isNotEmpty(), "raw-scan kind is a named Other")
            }
            NetworkId.Unidentified -> Unit // valid: no qualifying interface on this host
            is NetworkId.KindOnly -> throw AssertionError("desktop-JVM derivation never produces KindOnly")
        }
    }

    @Test
    fun currentPrimaryNetworkIdIsStableAcrossCalls() {
        // The same host state must map to the same identity, so a StateFlow dedupes it and auto-migration
        // only fires on a genuine change — not on every monitor tick.
        assertTrue(currentPrimaryNetworkId() == currentPrimaryNetworkId(), "identity must be stable across calls")
    }
}
