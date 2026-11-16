package com.ditchoom.socket.quic.core.udp

import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The buffer sizes asked of the operating system and what it granted, and the per-socket drop count. */
class SocketBuffersTests {
    @Test
    fun theDefaultsAreFourMebibytesToReceiveAndTwoToSend() {
        assertEquals(4 shl 20, SocketBuffers.Default.receiveBytes)
        assertEquals(2 shl 20, SocketBuffers.Default.sendBytes)
    }

    @Test
    fun aSocketReportsWhatTheOperatingSystemGranted() {
        DatagramChannel.open().use { channel ->
            val granted = SocketBuffers(receiveBytes = 1 shl 20, sendBytes = 1 shl 19).applyTo(channel)

            assertEquals(1 shl 20, granted.requested.receiveBytes)
            assertTrue(granted.receiveBytes > 0 && granted.sendBytes > 0, "the system answered a size for each buffer")
            assertEquals(channel.getOption(StandardSocketOptions.SO_RCVBUF), granted.receiveBytes)
            assertEquals(channel.getOption(StandardSocketOptions.SO_SNDBUF), granted.sendBytes)
        }
    }

    @Test
    fun dropsAreReadFromTheRowsOfTheSocketsPort() {
        val table =
            listOf(
                HEADER,
                "  91: 00000000:B7B8 00000000:0000 07 00000000:00000000 00:00000000 00000000  1000        0 52519 2 0000000000000000 831",
                "  92: 0100007F:1F90 00000000:0000 07 00000000:00000000 00:00000000 00000000  1000        0 52520 2 0000000000000000 7",
            )

        assertEquals(831L, dropsFor(table, port = 0xB7B8))
        assertEquals(7L, dropsFor(table, port = 0x1F90))
        assertEquals(0L, dropsFor(table, port = 1))
    }

    @Test
    fun aSocketsDropsAreTheSumOverBothTables() {
        val v4 = Files.createTempFile("udp", "")
        val v6 = Files.createTempFile("udp6", "")
        try {
            Files.write(v4, listOf(HEADER, "  1: 00000000:B7B8 00000000:0000 07 0:0 0:0 0 1000 0 1 2 0 5"))
            Files.write(
                v6,
                listOf(
                    HEADER,
                    "  1: 00000000000000000000000000000000:B7B8 00000000000000000000000000000000:0000 07 0:0 0:0 0 1000 0 1 2 0 826",
                ),
            )
            val report = SocketReport(granted(), localPort = 0xB7B8, tables = listOf(v4, v6))

            val drops = assertIs<SocketDrops.Counted>(report.drops())

            assertEquals(831L, drops.datagrams)
        } finally {
            Files.delete(v4)
            Files.delete(v6)
        }
    }

    @Test
    fun whereTheTablesDoNotExistTheCountIsNotAvailable() {
        val report = SocketReport(granted(), localPort = 1, tables = listOf(Path.of("/nonexistent/udp")))

        assertEquals(SocketDrops.NotAvailable, report.drops())
    }

    private fun granted() = GrantedBuffers(SocketBuffers.Default, receiveBytes = 1, sendBytes = 1)

    private companion object {
        const val HEADER = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode ref pointer drops"
    }
}
