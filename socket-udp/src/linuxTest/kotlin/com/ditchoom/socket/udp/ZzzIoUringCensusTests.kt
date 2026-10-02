package com.ditchoom.socket.udp

import kotlin.test.Test
import kotlin.test.assertEquals

// DIAGNOSTIC ONLY — never merge. Fails while any socket is still counted open, so CI uploads the
// test-results XML whose per-test system-out carries the IOURING-DIAG lines.
class ZzzIoUringCensusTests {
    @Test
    fun noSocketIsStillCounted() {
        assertEquals(0, IoUringManager.activeSockets, "IOURING-DIAG census")
    }
}
