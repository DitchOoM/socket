package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicAccept
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCandidateRace
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicPeerLeaf
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicRefusal
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSocketFault
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.StreamOpen
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.readEach
import com.ditchoom.socket.quic.serveQuicOnCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The public entry points on the core: a raced connect, a listener's accept and its handlers, refusals as values, the
 * stream limits [IncomingStreams] advertises, the application's abort codes on the wire, the block's end cutting its
 * plain-form streams, and the peer's leaf certificate in the handshake facts.
 */
class CoreEntryTests {
    @Test
    fun aConnectToTheListenerIsEchoedByItsHandler() =
        coreTest("CoreEntryTests.aConnectToTheListenerIsEchoedByItsHandler") {
            serving(serverOptions) { listener ->
                val serving = launch { listener.handleConnections { it.handleStreams(::echo) } }
                val connected = assertIs<QuicConnect.Connected<QuicConnection>>(connect(listener.port, clientOptions))
                assertEquals(QuicCandidateRace.Unopposed(local(listener.port)), connected.race)
                val client = connected.value
                assertEquals("test", client.handshake.negotiatedAlpn)
                val reply =
                    client
                        .openStream { (send, receive) ->
                            assertIs<StreamWrite.Written>(send.writeAndFinish(bytes(payload())))
                            collect(receive)
                        }.orFail()
                assertContentEquals(payload(), reply)
                client.close(QuicCloseChoice.Immediate())
                serving.cancel()
            }
        }

    @Test
    fun aDeadFirstCandidateCostsOneAttemptAndTheLiveOneWins() =
        coreTest("CoreEntryTests.aDeadFirstCandidateCostsOneAttemptAndTheLiveOneWins") {
            serving(serverOptions) { listener ->
                val dead = QuicEndpoint(ResolvedAddress("2001:db8::1", IpFamily.V6), listener.port)
                val live = local(listener.port)
                val started = TimeSource.Monotonic.markNow()
                val connected =
                    connectQuicOnCore(QuicPeer.Candidates(listOf(dead, live), "localhost"), clientOptions, TransportConfig(), 30.seconds)
                val elapsed = started.elapsedNow()
                val race = assertIs<QuicCandidateRace.Raced>(assertIs<QuicConnect.Connected<QuicConnection>>(connected).race)
                assertEquals(live, race.winner, "the documentation address routes nowhere; lost: ${race.lost}")
                assertEquals(listOf(dead), race.lost.map { it.endpoint })
                assertTrue(elapsed < 15.seconds, "a dead first candidate must cost one attempt, not the budget: $elapsed")
                connected.value.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun theListenerAcceptsEachConnectionInItsPublicShape() =
        coreTest("CoreEntryTests.theListenerAcceptsEachConnectionInItsPublicShape") {
            serving(serverOptions) { listener ->
                val client = connect(listener.port, clientOptions).getOrElse { throw AssertionError("refused: $it") }
                val accepted = assertIs<QuicAccept.Accepted>(withTimeout(WAIT) { listener.accept() })
                val server = accepted.connection
                assertEquals("test", server.handshake.negotiatedAlpn)
                assertIs<QuicPeerLeaf.NotPresented>(server.handshake.peerLeaf, "a client presents no certificate")
                val got = CompletableDeferred<ByteArray>()
                launch { server.handleStreams { incoming -> got.complete(collect(receiveOf(incoming))) } }
                client.openSendStream { send -> send.writeAndFinish(bytes(payload())) }.orFail()
                assertContentEquals(payload(), withTimeout(WAIT) { got.await() })
                client.close(QuicCloseChoice.Immediate())
                server.close(QuicCloseChoice.Immediate())
            }
            assertIs<QuicAccept.Closed>(acceptAfterClose(), "a closed listener accepts nothing")
            Unit
        }

    @Test
    fun anOptionTheCoreCannotHonourIsRefusedAsAValueBeforeAnythingOpens() =
        coreTest("CoreEntryTests.anOptionTheCoreCannotHonourIsRefusedAsAValueBeforeAnythingOpens") {
            val held = clientOptions.copy(standbyLink = StandbyLink.KeepCellularReady)
            var ran = 0
            val connect = connectQuicOnCore(QuicPeer.Candidates(listOf(local(1)), "localhost"), held, TransportConfig(), WAIT) { ran++ }
            assertEquals<QuicRefusal>(QuicRefusal.StandbyLinkNotHeld, assertIs<QuicConnect.Refused>(connect).refusal)
            assertEquals(0, ran, "the block of a refused connect does not run")
            val serve = serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), held) { ran++ }
            assertEquals<QuicRefusal>(QuicRefusal.StandbyLinkNotHeld, assertIs<QuicServe.Refused>(serve).refusal)
            assertEquals(0, ran)
        }

    @Test
    fun aClientThatRefusesStreamsAdvertisesZeroAndOneThatAcceptsTakesThem() =
        coreTest("CoreEntryTests.aClientThatRefusesStreamsAdvertisesZeroAndOneThatAcceptsTakesThem") {
            val bounded = serverOptions.copy(streamOpenPolicy = StreamOpenPolicy.Bounded(300.milliseconds))
            serving(bounded) { listener ->
                val opens = CompletableDeferred<List<StreamOpen.NotOpened>>()
                val serving =
                    launch {
                        listener.handleConnections { server ->
                            val both = server.openStream().fold({ null }, { it })
                            val send = server.openSendStream().fold({ null }, { it })
                            opens.complete(listOfNotNull(both, send))
                        }
                    }
                val refusing = connect(listener.port, clientOptions).getOrElse { throw AssertionError("refused: $it") }
                assertEquals<List<StreamOpen.NotOpened>>(
                    listOf(StreamOpen.NotOpened.DeadlinePassed, StreamOpen.NotOpened.DeadlinePassed),
                    withTimeout(WAIT) { opens.await() },
                    "a client's default refuses both kinds: the server can open none",
                )
                refusing.close(QuicCloseChoice.Immediate())
                serving.cancel()
            }
            serving(bounded) { listener ->
                val opened = CompletableDeferred<StreamWrite>()
                val serving =
                    launch {
                        listener.handleConnections { server ->
                            opened.complete(
                                server.openSendStream { it.writeAndFinish(bytes(payload())) }.getOrElse { throw AssertionError("$it") },
                            )
                        }
                    }
                val accepting = clientOptions.copy(incomingStreams = IncomingStreams.Accept())
                val client = connect(listener.port, accepting).getOrElse { throw AssertionError("refused: $it") }
                val got = CompletableDeferred<ByteArray>()
                launch { client.handleStreams { incoming -> got.complete(collect(receiveOf(incoming))) } }
                assertIs<StreamWrite.Written>(withTimeout(WAIT) { opened.await() })
                assertContentEquals(payload(), withTimeout(WAIT) { got.await() })
                client.close(QuicCloseChoice.Immediate())
                serving.cancel()
            }
        }

    @Test
    fun theApplicationsWriteCutCodeReachesThePeerWhenAWriteStopsPartWay() =
        coreTest("CoreEntryTests.theApplicationsWriteCutCodeReachesThePeerWhenAWriteStopsPartWay") {
            val narrow = serverOptions.copy(flowControl = FlowControl(initialMaxStreamDataBidiRemote = WINDOW.toLong()))
            serving(narrow) { listener ->
                val release = CompletableDeferred<Unit>()
                val why = CompletableDeferred<ScopedRead.NoData>()
                val serving =
                    launch {
                        listener.handleConnections { server ->
                            server.handleStreams { incoming ->
                                release.await()
                                why.complete(receiveOf(incoming).readEach { })
                            }
                        }
                    }
                val codes = QuicAbortCodes(writeCut = QuicAppErrorCode(CUT_CODE))
                val client = connect(listener.port, clientOptions.copy(abortCodes = codes)).getOrElse { throw AssertionError("$it") }
                val (send, _) = client.openStream().orFail()
                val cut = send.writeAndFinish(bytes(ByteArray(WINDOW * 4)), WritePolicy.Bounded(300.milliseconds))
                assertIs<StreamWrite.NotWritten.DeadlinePassed>(cut, "the peer's window holds a quarter of the bytes")
                release.complete(Unit)
                val reset = assertIs<ScopedRead.End.PeerReset>(withTimeout(WAIT) { why.await() })
                assertEquals(QuicAppErrorCode(CUT_CODE), reset.code)
                client.close(QuicCloseChoice.Immediate())
                serving.cancel()
            }
        }

    @Test
    fun theApplicationsBrokenFrameCodeReachesThePeerWhenATypedReadRefusesAFrame() =
        coreTest("CoreEntryTests.theApplicationsBrokenFrameCodeReachesThePeerWhenATypedReadRefusesAFrame") {
            val why = CompletableDeferred<ScopedRead.NoData>()
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    val stream = incoming as QuicStream
                    stream.receive.read { it.remaining() }
                    stream.send.write(frameOf(Note(NoteCodec.REJECTED_ID, "no")))
                    why.complete(stream.receive.readEach { })
                }
            }) { server ->
                val codes = QuicAbortCodes(brokenFrame = QuicAppErrorCode(BROKEN_CODE))
                val client = connectCore(server.port, options = coreTestOptions.copy(abortCodes = codes))
                val typed = client.connection.openTypedStream(NoteCodec)
                typed.send(Note(0, "go"))
                runCatching { typed.receive() }
                val reset = assertIs<ScopedRead.End.PeerReset>(withTimeout(WAIT) { why.await() })
                assertEquals(QuicAppErrorCode(BROKEN_CODE), reset.code)
                client.connection.close(com.ditchoom.socket.quic.QuicError.NoError)
            }
        }

    @Test
    fun theBlocksEndResetsItsPlainStreamsAndLeavesFinishedOnesAlone() =
        coreTest("CoreEntryTests.theBlocksEndResetsItsPlainStreamsAndLeavesFinishedOnesAlone") {
            serving(serverOptions) { listener ->
                val ends = CompletableDeferred<List<ScopedRead.NoData>>()
                val serving =
                    launch {
                        listener.handleConnections { server ->
                            val seen = ArrayList<ScopedRead.NoData>()
                            server.handleStreams { incoming ->
                                val end = receiveOf(incoming).readEach { }
                                synchronizedAdd(seen, end, ends)
                            }
                        }
                    }
                val codes = QuicAbortCodes(writeCut = QuicAppErrorCode(CUT_CODE))
                val ran =
                    connectQuicOnCore(
                        QuicPeer.Candidates(listOf(local(listener.port)), "localhost"),
                        clientOptions.copy(abortCodes = codes),
                        TransportConfig(),
                        WAIT,
                    ) { client ->
                        val (open, _) = client.openStream().orFail()
                        assertIs<StreamWrite.Written>(open.write(bytes(payload())))
                        val (finished, _) = client.openStream().orFail()
                        assertIs<StreamWrite.Written>(finished.writeAndFinish(bytes(payload())))
                        "returned"
                    }
                assertEquals("returned", assertIs<QuicConnect.Connected<String>>(ran).value)
                val seen = withTimeout(WAIT) { ends.await() }
                assertTrue(ScopedRead.End.PeerFinished in seen, "the finished stream ends as it was finished: $seen")
                val reset = seen.filterIsInstance<ScopedRead.End.PeerReset>().single()
                assertEquals(QuicAppErrorCode(CUT_CODE), reset.code, "the open plain stream is reset with the write-cut code")
                serving.cancel()
            }
        }

    @Test
    fun aStreamFinishedBeforeAGracefulCloseIsReadToItsFinAfterTheClose() =
        coreTest("CoreEntryTests.aStreamFinishedBeforeAGracefulCloseIsReadToItsFinAfterTheClose") {
            serving(serverOptions) { listener ->
                val ends = Channel<String>(Channel.UNLIMITED)
                val serving =
                    launch {
                        listener.handleConnections { server ->
                            server.handleStreams { incoming ->
                                // The reader starts only once the client's close ended the connection.
                                server.awaitEnd()
                                var bytes = 0
                                val end = receiveOf(incoming).readEach { bytes += it.remaining() }
                                ends.send("$bytes $end")
                            }
                        }
                    }
                repeat(ROUNDS) {
                    val ran =
                        connectQuicOnCore(
                            QuicPeer.Candidates(listOf(local(listener.port)), "localhost"),
                            clientOptions,
                            TransportConfig(),
                            WAIT,
                        ) { client ->
                            val (open, _) = client.openStream().orFail()
                            assertIs<StreamWrite.Written>(open.write(bytes(payload())))
                            val (finished, _) = client.openStream().orFail()
                            assertIs<StreamWrite.Written>(finished.writeAndFinish(bytes(payload())))
                        }
                    assertIs<QuicConnect.Connected<Unit>>(ran)
                    val seen = withTimeout(WAIT) { List(2) { ends.receive() } }
                    assertTrue("${payload().size} ${ScopedRead.End.PeerFinished}" in seen, "round $it: $seen")
                }
                serving.cancel()
            }
        }

    @Test
    fun theHandshakeFactsCarryTheSha256OfTheServersLeaf() =
        coreTest("CoreEntryTests.theHandshakeFactsCarryTheSha256OfTheServersLeaf") {
            mint().use { served ->
                withMintedCoreServer(served, onConnection = { }) { port ->
                    val client = connectCore(port, options = coreTestOptions.copy(serverCertificateHashes = listOf(served.hash)))
                    val leaf =
                        assertIs<QuicPeerLeaf.Presented>(
                            client.connection
                                .public()
                                .handshake.peerLeaf,
                        )
                    assertEquals(served.hash, leaf.sha256)
                    client.connection.close(com.ditchoom.socket.quic.QuicError.NoError)
                }
            }
            mint().use { served ->
                withMintedCoreServer(served, onConnection = { }) { port ->
                    // Read from quiche when nothing checks the chain after the handshake.
                    val client = connectCore(port)
                    val leaf =
                        assertIs<QuicPeerLeaf.Presented>(
                            client.connection
                                .public()
                                .handshake.peerLeaf,
                        )
                    assertEquals(served.hash, leaf.sha256)
                    client.connection.close(com.ditchoom.socket.quic.QuicError.NoError)
                }
            }
        }

    @Test
    fun theDatagramDoorSendsOnlyWhenDatagramsWereNegotiated() =
        coreTest("CoreEntryTests.theDatagramDoorSendsOnlyWhenDatagramsWereNegotiated") {
            serving(serverOptions) { listener ->
                val client = connect(listener.port, clientOptions).getOrElse { throw AssertionError("$it") }
                assertIs<DatagramSend.NotSent.Unavailable.NotNegotiated>(client.datagrams.send(bytes(payload(16))))
                client.close(QuicCloseChoice.Immediate())
            }
            val withDatagrams = serverOptions.copy(datagrams = DatagramOptions())
            serving(withDatagrams) { listener ->
                val client =
                    connect(listener.port, clientOptions.copy(datagrams = DatagramOptions())).getOrElse { throw AssertionError("$it") }
                assertIs<DatagramSend.Sent>(client.datagrams.send(bytes(payload(16))))
                client.close(QuicCloseChoice.Immediate())
            }
        }

    @Test
    fun aHandshakeThePeerRefusesIsAFailedValueWithTheEnd() =
        coreTest("CoreEntryTests.aHandshakeThePeerRefusesIsAFailedValueWithTheEnd") {
            serving(serverOptions.copy(alpnProtocols = listOf("other"))) { listener ->
                val failed = assertIs<QuicConnect.Failed>(connect(listener.port, clientOptions))
                val end = assertIs<QuicConnectionEnd.ByPeer>(failed.end, "the server refused the protocol: ${failed.end}")
                assertEquals<QuicError>(QuicError.CryptoError(ALERT_NO_APPLICATION_PROTOCOL), end.error)
            }
            mint().use { pinned ->
                mint().use { served ->
                    withMintedCoreServer(served, onConnection = { }) { port ->
                        val failed =
                            assertIs<QuicConnect.Failed>(connect(port, clientOptions.copy(serverCertificateHashes = listOf(pinned.hash))))
                        assertEquals<QuicConnectionEnd>(QuicConnectionEnd.ByLocal(QuicError.CryptoError(ALERT_BAD_CERTIFICATE)), failed.end)
                    }
                }
            }
        }

    @Test
    fun aListenerWhosePortIsTakenIsAFailedValue() =
        coreTest("CoreEntryTests.aListenerWhosePortIsTakenIsAFailedValue") {
            serving(serverOptions) { listener ->
                val taken = serveQuicOnCore(listener.port, LOOPBACK, platformTlsConfig(), serverOptions)
                val failed = assertIs<QuicServe.Failed>(taken)
                assertIs<QuicSocketFault.OpenRefused>(failed.fault)
            }
        }

    @Test
    fun aListenerOnTheDualStackWildcardIsReachedOverBothFamilies() =
        coreTest("CoreEntryTests.aListenerOnTheDualStackWildcardIsReachedOverBothFamilies") {
            val served =
                serveQuicOnCore(0, "::", platformTlsConfig(), serverOptions) { listener ->
                    val serving = launch { listener.handleConnections { it.handleStreams(::echo) } }
                    for (address in listOf(ResolvedAddress(LOOPBACK, IpFamily.V4), ResolvedAddress("::1", IpFamily.V6))) {
                        val endpoint = QuicEndpoint(address, listener.port)
                        val client =
                            connectQuicOnCore(QuicPeer.Candidates(listOf(endpoint), "localhost"), clientOptions, TransportConfig(), WAIT)
                                .getOrElse { throw AssertionError("$address did not reach the dual-stack listener: $it") }
                        val reply =
                            client
                                .openStream { (send, receive) ->
                                    send.writeAndFinish(bytes(payload()))
                                    collect(receive)
                                }.orFail()
                        assertContentEquals(payload(), reply, "echoed over $address")
                        client.close(QuicCloseChoice.Immediate())
                    }
                    serving.cancel()
                }
            assertIs<QuicServe.Serving<Unit>>(served)
            Unit
        }

    private var lastListener: QuicListener? = null

    private suspend fun acceptAfterClose(): QuicAccept = checkNotNull(lastListener).accept()

    private suspend fun serving(
        options: QuicOptions,
        block: suspend (QuicListener) -> Unit,
    ) {
        val served =
            serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), options) { listener ->
                lastListener = listener
                block(listener)
            }
        assertIs<QuicServe.Serving<Unit>>(served)
    }

    private suspend fun connect(
        port: Int,
        options: QuicOptions,
    ): QuicConnect<QuicConnection> =
        connectQuicOnCore(QuicPeer.Candidates(listOf(local(port)), "localhost"), options, TransportConfig(), WAIT)

    private fun local(port: Int) = QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), port)

    private fun <S> StreamOpen<S>.orFail(): S = getOrElse { throw AssertionError("no stream was opened: $it") }

    private fun receiveOf(incoming: QuicIncomingStream): QuicReceiveStream =
        when (incoming) {
            is QuicStream -> incoming.receive
            is QuicReceiveStream -> incoming
        }

    private suspend fun echo(incoming: QuicIncomingStream) {
        when (incoming) {
            is QuicStream -> incoming.receive.readEach { incoming.send.write(it) }
            is QuicReceiveStream -> incoming.readEach { }
        }
    }

    private suspend fun collect(receive: QuicReceiveStream): ByteArray {
        val got = ReceivedBytes(PAYLOAD)
        assertEquals<ScopedRead.NoData>(ScopedRead.End.PeerFinished, withTimeout(WAIT) { receive.readEach { got.add(it) } })
        return got.toByteArray()
    }

    private fun bytes(content: ByteArray): ReadBuffer =
        BufferFactory.network().allocate(content.size).apply {
            writeBytes(content)
            resetForRead()
        }

    private fun payload(size: Int = PAYLOAD) = ByteArray(size) { it.toByte() }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PAYLOAD = 5_000
        const val WINDOW = 4_096
        const val CUT_CODE = 0x99L
        const val BROKEN_CODE = 0x5BL
        const val ROUNDS = 20
        const val ALERT_NO_APPLICATION_PROTOCOL = 120
        const val ALERT_BAD_CERTIFICATE = 42
        val WAIT = 10.seconds
        val clientOptions = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
        val serverOptions = clientOptions
    }
}

/** Adds [end] to [seen], completing [all] once two streams have ended; handlers of one connection never run at once. */
private fun synchronizedAdd(
    seen: MutableList<ScopedRead.NoData>,
    end: ScopedRead.NoData,
    all: CompletableDeferred<List<ScopedRead.NoData>>,
) {
    seen += end
    if (seen.size == 2) all.complete(seen.toList())
}
