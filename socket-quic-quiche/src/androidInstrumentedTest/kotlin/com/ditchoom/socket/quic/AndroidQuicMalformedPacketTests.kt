package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Android (quiche JNI) member of the shared [QuicMalformedPacketTestSuite] — the Android counterpart of
 * [QuicMalformedPacketTests] (JVM) / [LinuxQuicMalformedPacketTests]. Replaces the former hand-copy,
 * which duplicated the `MALFORMED_DATAGRAMS` corpus locally; extending the suite inherits the single
 * source of truth. Provides the raw-UDP `sendRawDatagram` primitive via NIO `DatagramChannel`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicMalformedPacketTests : QuicMalformedPacketTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    ) {
        DatagramChannel.open().use { channel ->
            channel.send(ByteBuffer.wrap(bytes), InetSocketAddress("127.0.0.1", port))
        }
    }

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
