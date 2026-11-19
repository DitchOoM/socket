package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicSendRefusal
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.core.udp.UdpError
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A client whose every datagram the network refuses ends its handshake saying nothing could be sent, and why. */
class CoreRefusedSendsTests {
    private class RefusesEverySend(
        private val real: DatagramBatchIo,
        private val why: UdpError,
    ) : DatagramBatchIo by real {
        override fun send(batch: SendBatch): SendBatchResult = SendBatchResult.Partial(0, why)
    }

    private suspend fun connectEnd(why: UdpError): QuicConnectionEnd =
        try {
            coreConnect(
                host = "127.0.0.1",
                port = 9,
                serverName = "localhost",
                options = coreTestOptions.copy(handshakePolicy = HandshakePolicy.Bounded(500.milliseconds)),
                factory = BufferFactory.Default,
                api = loadQuicheApi(),
                timeout = 5.seconds,
                readPolicy = ReadPolicy.Bounded(5.seconds),
                writePolicy = WritePolicy.Bounded(5.seconds),
                wrapIo = { RefusesEverySend(it, why) },
                flight = TEST_FLIGHT,
            )
            error("the connect cannot complete when nothing is sent")
        } catch (failed: QuicCloseException) {
            failed.end
        }

    @Test
    fun aHandshakeWhoseEverySendWasRefusedCarriesTheRefusal() =
        runBlocking {
            skipOnMissingNativeLib(CoreRefusedSendsTests::class) {
                val end = assertIs<QuicConnectionEnd.HandshakeSendRefused>(connectEnd(UdpError.PermissionDenied))
                assertEquals(QuicSendRefusal.PermissionDenied, end.refusal)
            }
        }

    @Test
    fun anUnnamedRefusalKeepsItsNativeCode() =
        runBlocking {
            skipOnMissingNativeLib(CoreRefusedSendsTests::class) {
                val end =
                    assertIs<QuicConnectionEnd.HandshakeSendRefused>(
                        connectEnd(UdpError.Unnamed(Thrown(NativeSocketRefusal(SocketRefusal.EPipe)))),
                    )
                val unnamed = assertIs<QuicSendRefusal.Unnamed>(end.refusal)
                assertEquals(SocketRefusal.EPipe, assertIs<NativeSocketRefusal>(unnamed.thrown).refusal)
            }
        }
}
