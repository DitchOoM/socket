package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The qlog seam is the only frame-level evidence this library can produce, and it was reachable from
 * exactly one place: the process environment. That is not where the traces worth taking happen — an
 * Android instrumentation run passes extras, never environment, so a device recording a real
 * Wi-Fi↔cellular handoff (#437) could not turn it on at all.
 */
class QlogEnvTests {
    private inline fun withProperty(
        value: String?,
        block: () -> Unit,
    ) {
        val previous = System.getProperty(QLOG_DIR_PROPERTY)
        if (value == null) System.clearProperty(QLOG_DIR_PROPERTY) else System.setProperty(QLOG_DIR_PROPERTY, value)
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty(QLOG_DIR_PROPERTY) else System.setProperty(QLOG_DIR_PROPERTY, previous)
        }
    }

    @Test
    fun systemPropertyReachesWhereAnEnvironmentVariableCannot() {
        withProperty("/tmp/qlog-from-a-property") {
            assertEquals("/tmp/qlog-from-a-property", qlogDir())
        }
    }

    @Test
    fun aBlankPropertyIsNotADirectory() {
        // Blank means "not set" on both channels, so a blank property must fall through rather than
        // handing quiche an empty path — the same `isNotBlank` rule the env var has always had.
        withProperty("   ") {
            assertNotEquals("   ", qlogDir())
        }
    }

    private companion object {
        const val QLOG_DIR_PROPERTY = "quic.qlog.dir"
    }
}
