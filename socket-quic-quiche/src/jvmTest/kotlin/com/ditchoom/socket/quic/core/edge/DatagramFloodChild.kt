package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The sender of [DatagramBatchReceiveCountsTests]' flood, in a JVM of its own, so the reader under test is not also
 * paying for the sender's CPU and allocations. It connects to the port it is given and prints [READY]; each `flood <ms>`
 * line on its stdin floods 1000-byte datagrams as fast as the send queue takes them for that long, then prints
 * [FLOODED]. End of stdin (the test closing it, or the test JVM dying) closes the connection and exits.
 */
internal object DatagramFloodChild {
    const val READY = "flood-child: ready"
    const val FLOODED = "flood-child: flooded"
    const val FLOOD_BYTES = 1000
    val OPTIONS = coreTestOptions.copy(datagrams = DatagramOptions())

    @JvmStatic
    fun main(args: Array<String>) {
        // Dies with the test: a JVM whose parent is gone has no one to read its flood.
        ProcessHandle.current().parent().ifPresent { parent -> parent.onExit().thenRun { Runtime.getRuntime().halt(2) } }
        val port = args.single().toInt()
        runBlocking {
            val loops = LoopSet(1)
            val client = connectCore(port, loops = loops, factory = BufferFactory.network(), options = OPTIONS)
            val payload =
                BufferFactory.network().allocate(FLOOD_BYTES).apply {
                    repeat(FLOOD_BYTES) { writeByte(it.toByte()) }
                    resetForRead()
                }
            println(READY)
            val input = System.`in`.bufferedReader()
            while (true) {
                val line = runInterruptible(Dispatchers.IO) { input.readLine() } ?: break
                flood(client.connection.coreDatagrams, payload, line.removePrefix("flood ").toLong())
                println(FLOODED)
            }
            client.connection.close(QuicError.NoError)
            payload.freeNativeMemory()
            loops.close()
        }
        exitProcess(0)
    }

    /** Sends [payload] as fast as the send queue takes it for [millis], waiting for room when it is full. */
    private suspend fun flood(
        channel: CoreDatagramChannel,
        payload: PlatformBuffer,
        millis: Long,
    ) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            payload.position(0)
            when (channel.send(payload)) {
                is DatagramSend.Sent -> Unit
                is DatagramSend.NotSent.QueueFull -> channel.awaitRoom()
                is DatagramSend.NotSent -> error("the flood's send was refused")
            }
        }
        yield()
    }
}

/**
 * A running [DatagramFloodChild]: started on the test's classpath and JVM options, its output lines read on a daemon
 * thread. [close] ends it: stdin closed, then killed if it has not exited within five seconds.
 */
internal class FloodSender private constructor(
    private val process: Process,
) : AutoCloseable {
    private val lines = Channel<String>(Channel.UNLIMITED)

    init {
        Thread({
            process.inputReader().useLines { all ->
                all.forEach { line ->
                    println("[flood child] $line")
                    lines.trySend(line)
                }
            }
            lines.close()
        }, "flood-child-output").apply { isDaemon = true }.start()
    }

    /** Waits for [marker] on the child's output. */
    suspend fun await(
        marker: String,
        within: Duration = 30.seconds,
    ) = withTimeout(within) {
        while (lines.receive() != marker) Unit
    }

    /** Floods for [millis] and returns once the child has. */
    suspend fun flood(millis: Long) {
        process.outputWriter().apply {
            write("flood $millis\n")
            flush()
        }
        await(DatagramFloodChild.FLOODED, (millis / 1_000 + 30).seconds)
    }

    override fun close() {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
    }

    companion object {
        /** Starts a child flooding the server at [port], with this JVM's native-access and opens options. */
        fun start(port: Int): FloodSender {
            val options =
                ManagementFactory.getRuntimeMXBean().inputArguments.filter {
                    it.startsWith("--enable-native-access") || it.startsWith("--add-opens")
                }
            val java = File(System.getProperty("java.home"), "bin/java").path
            val command =
                listOf(java) + options +
                    listOf("-cp", System.getProperty("java.class.path"), DatagramFloodChild::class.java.name, "$port")
            return FloodSender(ProcessBuilder(command).redirectErrorStream(true).start())
        }
    }
}
