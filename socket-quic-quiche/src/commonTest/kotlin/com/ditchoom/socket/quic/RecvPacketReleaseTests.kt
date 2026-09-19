package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A queued receive owns its buffer until exactly one door releases it (#588, #578).
 *
 * The field signature was a `RecvPacket` whose pool wrapper had already been returned to the pool
 * while the packet was still deliverable — a second owner, found only when the driver dereferenced
 * the wrapper under whichever command came next. The packet's lifetime is a sealed state so the
 * second release is named where it happens, with both doors, and cannot run the server's
 * `onConsumed` a second time (which is how a double release stayed invisible to the recv_info
 * in-flight accounting).
 */
class RecvPacketReleaseTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun theFirstReleaseTakesTheBufferAndTheServersInFlightReference() {
        var consumed = 0
        val packet = QuicheCmd.RecvPacket(bufferFactory.allocate(16), 16, PacketSource.FromServerSocket(QuicheRecvInfo(1L)) { consumed++ })
        assertEquals(QuicheCmd.PacketLifetime.Held, packet.lifetime)

        packet.release(QuicheCmd.ReleaseDoor.Executed)

        assertEquals(QuicheCmd.PacketLifetime.Released(QuicheCmd.ReleaseDoor.Executed), packet.lifetime)
        assertEquals(1, consumed, "the server's in-flight reference is returned exactly once")
    }

    @Test
    fun aSecondReleaseIsNamedWithBothDoorsAndConsumesNothingAgain() {
        var consumed = 0
        val packet = QuicheCmd.RecvPacket(bufferFactory.allocate(16), 16, PacketSource.FromServerSocket(QuicheRecvInfo(1L)) { consumed++ })
        packet.release(QuicheCmd.ReleaseDoor.Failed)

        val twice = assertFailsWith<RecvPacketReleasedTwice> { packet.release(QuicheCmd.ReleaseDoor.Executed) }

        assertEquals("a queued receive was released twice: first Failed, then Executed", twice.message)
        assertEquals(1, consumed, "a second release must not return the in-flight reference again")
        assertIs<QuicheCmd.PacketLifetime.Released>(packet.lifetime)
    }

    @Test
    fun aPathPacketOwesNoServerReference() {
        val packet = QuicheCmd.RecvPacket(bufferFactory.allocate(16), 16, PacketSource.FromPath(PathKey(2, 1, 0L, 0L)))
        packet.release(QuicheCmd.ReleaseDoor.Refused)
        assertEquals(QuicheCmd.PacketLifetime.Released(QuicheCmd.ReleaseDoor.Refused), packet.lifetime)
    }
}
