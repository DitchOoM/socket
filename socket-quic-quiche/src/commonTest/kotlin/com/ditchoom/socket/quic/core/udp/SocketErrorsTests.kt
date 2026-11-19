package com.ditchoom.socket.quic.core.udp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The one classifier of send errors (design section 8.3): values in, values out. */
class SocketErrorsTests {
    private val errors = SocketErrors()
    private val unnamed = UdpError.Unnamed(Thrown(IllegalStateException()))

    @Test
    fun aFullSocketIsToBeWaitedFor() {
        assertSame(SendFault.Full, errors.classify(UdpError.WouldBlock, SocketProbe.Open))
    }

    @Test
    fun everyNamedErrorIsThatDatagramsAlone() {
        val expected =
            mapOf(
                UdpError.MessageTooLarge to DatagramFault.TooLarge,
                UdpError.NetworkUnreachable to DatagramFault.NetworkUnreachable,
                UdpError.HostUnreachable to DatagramFault.HostUnreachable,
                UdpError.PortUnreachable to DatagramFault.PortUnreachable,
                UdpError.NoBufferSpace to DatagramFault.NoBufferSpace,
                UdpError.PermissionDenied to DatagramFault.PermissionDenied,
            )
        for ((error, why) in expected) {
            assertEquals(why, assertIs<SendFault.Datagram>(errors.classify(error, SocketProbe.Open)).why)
        }
    }

    @Test
    fun aDatagramFaultIsOnePreallocatedInstance() {
        assertSame(
            errors.classify(UdpError.PortUnreachable, SocketProbe.Open),
            errors.classify(UdpError.PortUnreachable, SocketProbe.Open),
        )
    }

    @Test
    fun aClosedSocketIsTheSocketsEnd() {
        val fault = assertIs<SendFault.Socket>(errors.classify(UdpError.SocketClosed, SocketProbe.Open))
        assertSame(SocketFault.Closed, fault.fault)
    }

    /** The Android case: the platform throws something unnamed and the channel reports closed. */
    @Test
    fun anUnnamedErrorOnASocketThatReportsClosedEndsTheSocketWithThatError() {
        val fault = assertIs<SendFault.Socket>(errors.classify(unnamed, SocketProbe.Closed))
        assertSame(unnamed.thrown, assertIs<SocketFault.Unnamed>(fault.fault).thrown)
    }

    @Test
    fun anUnnamedErrorOnAnOpenSocketIsThatDatagramsAlone() {
        val fault = assertIs<SendFault.Datagram>(errors.classify(unnamed, SocketProbe.Open))
        assertEquals(DatagramFault.Unnamed, fault.why)
        assertSame(unnamed.thrown, assertIs<SendRefusal.Unnamed>(fault.refusal).thrown)
    }

    @Test
    fun eightUnnamedRefusalsInARowEndTheSocketAndSevenDoNot() {
        repeat(SocketErrors.REPEATED_LIMIT - 1) {
            assertIs<SendFault.Datagram>(errors.classify(unnamed, SocketProbe.Open))
        }
        val fault = assertIs<SendFault.Socket>(errors.classify(unnamed, SocketProbe.Open))
        val repeated = assertIs<SocketFault.Repeated>(fault.fault)
        assertEquals(SocketErrors.REPEATED_LIMIT, repeated.count)
        assertSame(unnamed.thrown, repeated.thrown)
    }

    @Test
    fun aDatagramTakenInBetweenBreaksTheRun() {
        repeat(SocketErrors.REPEATED_LIMIT - 1) { errors.classify(unnamed, SocketProbe.Open) }
        errors.taken()
        repeat(SocketErrors.REPEATED_LIMIT - 1) {
            assertIs<SendFault.Datagram>(errors.classify(unnamed, SocketProbe.Open))
        }
    }

    @Test
    fun namedRefusalsDoNotCountTowardsTheRun() {
        repeat(SocketErrors.REPEATED_LIMIT - 1) { errors.classify(unnamed, SocketProbe.Open) }
        repeat(20) { errors.classify(UdpError.PortUnreachable, SocketProbe.Open) }
        assertIs<SendFault.Socket>(errors.classify(unnamed, SocketProbe.Open))
    }
}
