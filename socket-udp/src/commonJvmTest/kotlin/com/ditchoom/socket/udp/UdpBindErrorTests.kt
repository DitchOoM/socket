package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import java.net.BindException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A refused bind says why, as a value: "that port is taken" and "that address is not this host's" are
 * the two a caller binding many addresses must tell apart — one is worth another draw, the other never
 * is — and the JDK raises the same `BindException` for both.
 */
@OptIn(ExperimentalDatagramApi::class)
class UdpBindErrorTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    @Test
    fun aBindOnAHeldPortReportsAddressInUse() =
        runBlocking {
            val holder = UdpSocket.bind("127.0.0.1", 0).also { opened += it }
            val port = holder.localAddress.port

            val refused = runCatching { UdpSocket.bind("127.0.0.1", port).also { opened += it } }.exceptionOrNull()

            val bindFailure = assertIs<UdpBindException>(refused, "a refused bind must be typed; got $refused")
            assertEquals(UdpBindError.AddressInUse(ERRNO_NOT_SURFACED), bindFailure.error)
            assertEquals("127.0.0.1", bindFailure.host)
            assertEquals(port, bindFailure.port)
        }

    @Test
    fun aBindToAnAddressThisHostDoesNotHaveReportsLocalAddressUnavailable() =
        runBlocking {
            // 192.0.2.0/24 is TEST-NET-1 (RFC 5737): never assigned to a host.
            val refused = runCatching { UdpSocket.bind("192.0.2.1", 0).also { opened += it } }.exceptionOrNull()

            val bindFailure = assertIs<UdpBindException>(refused, "a refused bind must be typed; got $refused")
            assertEquals(UdpBindError.LocalAddressUnavailable(ERRNO_NOT_SURFACED), bindFailure.error)
        }

    /**
     * The JDK files `EADDRNOTAVAIL` under `BindException` too (`Net.c`), with Darwin's phrase "Can't
     * assign requested address" and glibc's "Cannot assign requested address" — measured on JDK 21,
     * macOS. The type alone says nothing; the phrase decides.
     */
    @Test
    fun aConnectsBindExceptionForAnUnavailableAddressIsNotAddressInUse() {
        assertEquals(
            UdpConnectError.LocalAddressUnavailable(ERRNO_NOT_SURFACED),
            jvmConnectErrorOf(BindException("Can't assign requested address")),
        )
        assertEquals(
            UdpConnectError.LocalAddressUnavailable(ERRNO_NOT_SURFACED),
            jvmConnectErrorOf(BindException("Cannot assign requested address")),
        )
        assertEquals(UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED), jvmConnectErrorOf(BindException("Address already in use")))
    }
}
