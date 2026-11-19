package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** quiche takes a hashed certificate directory as the verification anchors, through the cinterop binding. */
class LinuxSystemTrustDirectoryTests {
    @Test
    fun quicheLoadsTheHashedCertificateDirectory() {
        val api = CinteropQuicheApi
        val config = api.configNew(1)
        try {
            assertEquals(0, loadAnchorDirectory(api, config, "/etc/ssl/certs", BufferFactory.network()))
        } finally {
            api.configFree(config)
        }
    }
}
