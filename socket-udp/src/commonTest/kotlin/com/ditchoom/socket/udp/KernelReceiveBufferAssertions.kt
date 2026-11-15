package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A request the host can meet is granted in full, and one no host can meet is reported as short of what was
 * asked, never as granted. Shared by the suites of the platforms whose sockets this library sizes.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun assertAKernelReceiveBufferRequestIsAnsweredWithWhatTheHostGave() {
    val socket = UdpSocket.bind("127.0.0.1", 0)
    try {
        val modest = assertIs<KernelBufferGrant.Granted>(socket.requestKernelReceiveBuffer(MODEST_BYTES))
        assertTrue(modest.usableBytes >= MODEST_BYTES, "a granted buffer holds what was asked: $modest")

        when (val beyond = socket.requestKernelReceiveBuffer(BEYOND_ANY_HOST_BYTES)) {
            is KernelBufferGrant.Capped ->
                assertTrue(beyond.usableBytes in MODEST_BYTES until BEYOND_ANY_HOST_BYTES, "a capped buffer reports its size: $beyond")
            is KernelBufferGrant.Unread -> Unit
            is KernelBufferGrant.Granted, KernelBufferGrant.Unsupported ->
                throw AssertionError("a gigabyte of receive buffer was reported as $beyond")
        }
    } finally {
        socket.close()
    }
}

private const val MODEST_BYTES = 64 * 1024
private const val BEYOND_ANY_HOST_BYTES = 1 shl 30
