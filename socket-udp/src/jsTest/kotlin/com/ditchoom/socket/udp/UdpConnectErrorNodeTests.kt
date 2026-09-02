package com.ditchoom.socket.udp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Node member of the #534 contract: the `error` event's `EADDRINUSE` name classifies onto the sealed reason. */
class UdpConnectErrorNodeTests {
    @Test
    fun aConnectOnAHeldLocalPortReportsAddressInUse() = runTest { assertConnectOnAHeldLocalPortReportsAddressInUse() }
}
