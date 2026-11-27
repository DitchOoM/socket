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
        val host = HostErrnos.Linux
        assertEquals(UdpConnectError.AddressInUse(98), jvmConnectErrorOf(BindException("Address already in use"), host))
        assertEquals(UdpConnectError.Unreachable(113), jvmConnectErrorOf(NoRouteToHostException("No route to host"), host))
        assertEquals(UdpConnectError.Unreachable(101), jvmConnectErrorOf(SocketException("Network is unreachable"), host))
        assertEquals(
            UdpConnectError.LocalAddressUnavailable(99),
            jvmConnectErrorOf(SocketException("Cannot assign requested address"), host),
        )
        assertEquals(UdpConnectError.NotPermitted(13), jvmConnectErrorOf(SocketException("Permission denied"), host))
        assertEquals(UdpConnectError.SocketUnavailable(24), jvmConnectErrorOf(SocketException("Too many open files"), host))
        assertEquals(UdpConnectError.SocketUnavailable(23), jvmConnectErrorOf(SocketException("Too many open files in system"), host))
        val unknown = SocketException("something the JDK invented")
        assertEquals(UdpConnectError.Transport(unknown), jvmConnectErrorOf(unknown, host))
        assertEquals(UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED), jvmConnectErrorOf(BindException("unphrased"), host))
    }

    /**
     * Windows' JDK renders each Winsock code as one fixed phrase (`NET_ThrowNew`'s table); a port-0 bind
     * with the dynamic range exhausted reports `WSAENOBUFS` from `bind0` in exactly this form, measured
     * on a windows-latest runner holding 15000 UDP sockets.
     */
    @Test
    fun windowsPhrasesCarryTheirWinsockCode() {
        val host = HostErrnos.Windows
        val exhausted = SocketException("No buffer space available (maximum connections reached?): bind")
        assertEquals(UdpConnectError.SocketUnavailable(10055), jvmConnectErrorOf(exhausted, host))
        assertEquals(UdpBindError.SocketUnavailable(10055), jvmBindErrorOf(exhausted, host))
        assertEquals(UdpConnectError.AddressInUse(10048), jvmConnectErrorOf(BindException("Address already in use: connect"), host))
        assertEquals(UdpConnectError.SocketUnavailable(10024), jvmConnectErrorOf(SocketException("Too many open files"), host))
        assertEquals(UdpConnectError.NotPermitted(10013), jvmConnectErrorOf(BindException("Permission denied: connect"), host))
        assertEquals(UdpConnectError.Unreachable(10065), jvmConnectErrorOf(NoRouteToHostException("No route to host: connect"), host))
    }

    @Test
    fun darwinPhrasesCarryDarwinNumbers() {
        val host = HostErrnos.Darwin
        assertEquals(UdpConnectError.AddressInUse(48), jvmConnectErrorOf(BindException("Address already in use"), host))
        assertEquals(UdpConnectError.LocalAddressUnavailable(49), jvmConnectErrorOf(BindException("Can't assign requested address"), host))
        assertEquals(UdpConnectError.SocketUnavailable(55), jvmConnectErrorOf(SocketException("No buffer space available"), host))
    }

    /** An OS the table does not know still classifies every phrase; only the number is withheld. */
    @Test
    fun anUnknownHostClassifiesWithoutANumber() {
        assertEquals(
            UdpConnectError.SocketUnavailable(ERRNO_NOT_SURFACED),
            jvmConnectErrorOf(SocketException("No buffer space available"), HostErrnos.Unknown),
        )
    }
}
