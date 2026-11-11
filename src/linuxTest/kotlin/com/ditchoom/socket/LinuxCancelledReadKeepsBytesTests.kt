package com.ditchoom.socket

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A recv that completed with bytes took them out of the socket, so a reader cancelled before it resumes must
 * not lose them: the next read returns them. Each test steps the reader's dispatcher so the cancellation lands
 * exactly between the recv's completion and the reader's resumption.
 */
class LinuxCancelledReadKeepsBytesTests {
    /** The connecting side's read ([LinuxClientSocket]). */
    @Test
    fun aClientReadCancelledAfterItsRecvCompletedLeavesTheBytesForTheNextRead() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val server = ServerSocket.allocate()
            val accepted = server.bind()
            val serverSide = CompletableDeferred<ClientSocket>()
            val collection = launch { accepted.collect { serverSide.complete(it) } }
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            try {
                client.open(server.port(), "127.0.0.1")
                val peer = serverSide.await()
                assertCancelledReadKeepsBytes(reader = client, writer = peer)
                peer.close()
            } finally {
                collection.cancel()
                client.close()
                server.close()
            }
        }

    /** The accepting side's read ([LinuxSocketWrapper]). */
    @Test
    fun anAcceptedSocketReadCancelledAfterItsRecvCompletedLeavesTheBytesForTheNextRead() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val server = ServerSocket.allocate()
            val accepted = server.bind()
            val serverSide = CompletableDeferred<ClientSocket>()
            val collection = launch { accepted.collect { serverSide.complete(it) } }
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            try {
                client.open(server.port(), "127.0.0.1")
                val peer = serverSide.await()
                assertCancelledReadKeepsBytes(reader = peer, writer = client)
                peer.close()
            } finally {
                collection.cancel()
                client.close()
                server.close()
            }
        }

    private suspend fun assertCancelledReadKeepsBytes(
        reader: ClientSocket,
        writer: ClientSocket,
    ) {
        val stepped = SteppedDispatcher()
        val cancelled = CoroutineScope(stepped).async { reader.read(10.seconds) }
        stepped.next().run() // the read starts and parks in its recv
        writer.writeString("kept-bytes")
        val resumption = stepped.next() // the recv completed with the bytes; the reader has not resumed
        cancelled.cancel()
        resumption.run()
        stepped.runQueued()
        assertTrue(cancelled.isCancelled, "the reader was cancelled before it resumed")
        val text =
            try {
                reader.readString(deadline = 2.seconds)
            } catch (e: Throwable) {
                fail("the bytes the cancelled recv took from the socket were dropped; the next read failed with: $e")
            }
        assertEquals("kept-bytes", text)
    }
}
