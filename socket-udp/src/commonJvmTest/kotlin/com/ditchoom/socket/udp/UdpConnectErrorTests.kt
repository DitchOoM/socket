package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import java.net.BindException
import java.net.NoRouteToHostException
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM/Android member of the #534 contract, plus the JDK-type classification it rests on. */
class UdpConnectErrorTests {
    @Test
    fun aConnectOnAHeldLocalPortReportsAddressInUse() = runBlocking { assertConnectOnAHeldLocalPortReportsAddressInUse() }

    @Test
    fun theJdkTypesAndPhrasesClassifyOntoTheSealedReason() {
        assertEquals(UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED), jvmConnectErrorOf(BindException("Address already in use")))
        assertEquals(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED), jvmConnectErrorOf(NoRouteToHostException("No route to host")))
        assertEquals(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED), jvmConnectErrorOf(SocketException("Network is unreachable")))
        assertEquals(
            UdpConnectError.LocalAddressUnavailable(ERRNO_NOT_SURFACED),
            jvmConnectErrorOf(SocketException("Cannot assign requested address")),
        )
        assertEquals(UdpConnectError.NotPermitted(ERRNO_NOT_SURFACED), jvmConnectErrorOf(SocketException("Permission denied")))
        assertEquals(UdpConnectError.SocketUnavailable(ERRNO_NOT_SURFACED), jvmConnectErrorOf(SocketException("Too many open files")))
        val unknown = SocketException("something the JDK invented")
        assertEquals(UdpConnectError.Transport(unknown), jvmConnectErrorOf(unknown))
    }
}
