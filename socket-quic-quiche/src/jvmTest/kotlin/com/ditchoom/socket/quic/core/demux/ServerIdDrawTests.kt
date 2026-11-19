package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A server's loop draws the ids it hands its connections (each accepted connection's own id in the receive phase, its
 * spare ids after) from a source no other thread draws from. A process-wide source is a lock every client thread
 * takes too: `SecureRandom` synchronises its draws, and on Android 9 and 10, two vCPUs and a herd of client threads,
 * a loop that waits for a preempted holder holds up the whole receive phase while the kernel drops what arrives.
 */
class ServerIdDrawTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun theServerLoopNeverWaitsOnAnotherThreadsIdDraw() =
        runBlocking {
            withCoreEchoServer { server ->
                // Other threads draw ids as fast as they can, as a herd of connecting clients does.
                val drawing = AtomicInteger(DRAWERS)
                val drawers = List(DRAWERS) { thread(name = "id-drawer-$it") { while (drawing.get() > 0) connectionIdRandom.nextLong() } }
                val recording = Recording()
                recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace()
                recording.start()
                try {
                    (0 until CLIENTS)
                        .map {
                            async(Dispatchers.IO) {
                                val client = connectCore(server.port, loops = loops)
                                client.connection.close(QuicError.NoError)
                            }
                        }.awaitAll()
                } finally {
                    recording.stop()
                    drawing.set(0)
                    drawers.forEach { it.join() }
                }
                val waits = idWaitsOf(recording, server.loop.name)
                assertEquals(
                    0,
                    waits.size,
                    "the server loop waited on another thread's id draw ${waits.size} times:\n${waits.take(3).joinToString("\n")}",
                )
            }
        }

    /** The server loop's monitor waits inside a `SecureRandom` draw. */
    private fun idWaitsOf(
        recording: Recording,
        loopName: String,
    ): List<String> {
        val dump = Files.createTempFile("server-id-draws", ".jfr")
        recording.dump(dump)
        val waits =
            RecordingFile
                .readAllEvents(dump)
                .filter { it.eventType.name == "jdk.JavaMonitorEnter" && it.thread?.javaName == loopName }
                .map { e ->
                    e.stackTrace?.frames.orEmpty().joinToString(
                        " < ",
                    ) { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                }.filter { it.contains("SecureRandom") }
        Files.delete(dump)
        return waits
    }

    private companion object {
        const val DRAWERS = 4
        const val CLIENTS = 128
    }
}
