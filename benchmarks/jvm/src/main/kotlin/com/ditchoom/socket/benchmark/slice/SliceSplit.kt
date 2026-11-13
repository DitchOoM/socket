package com.ditchoom.socket.benchmark.slice

import com.ditchoom.socket.benchmark.assertQuicheBackend
import io.netty.handler.codec.quic.QuicStreamChannel
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * The server alone in one process and the load in another (on another machine): the datagrams cross a real
 * network and the server's CPU is its own process's.
 *
 * A server prints READY, then every quarter second `cpu <epoch ms> <process CPU ns>`; a client prints its result
 * line and `window <from epoch ms> <to epoch ms> ops <n> ...`. The server's CPU per operation is the CPU its
 * process used between the window's bounds over the operations of all clients.
 */

private val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

/** Prints READY and then this process's CPU time four times a second, until the process is killed. */
internal fun serveUntilKilled() {
    println("READY")
    System.out.flush()
    while (true) {
        println("cpu ${System.currentTimeMillis()} ${osBean.processCpuTime}")
        System.out.flush()
        Thread.sleep(250)
    }
}

private fun targets(
    host: String,
    basePort: Int,
    count: Int,
): List<InetSocketAddress> = List(count) { InetSocketAddress(host, basePort + it) }

/** args: placement, hold, bind host, base port, server loops. */
internal object SliceServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val loopback =
            SliceLoopback(
                args[0],
                holdAck = args[1].toBoolean(),
                callerRuns = false,
                connectionCount = 0,
                clientLoops = 0,
                serverLoops = args.getOrElse(4) { "1" }.toInt(),
                serverBind = args[2],
                serverBasePort = args[3].toInt(),
            )
        println("# slice server: placement=${args[0]} hold=${args[1]} real=${System.getProperty("slice.real") ?: "off"} backend=${assertQuicheBackend()}")
        Runtime.getRuntime().addShutdownHook(Thread { println(loopback.report(1)) })
        serveUntilKilled()
    }
}

/** args: host, base port, server loops, connections, warm-up s, measured s, callerRuns, hold, client loops, start at (epoch ms), mode (rt|spr). */
internal object SliceClientMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val m = args[3].toInt()
        val mode = args.getOrElse(10) { "rt" }
        val loopback =
            SliceLoopback(
                "loop",
                holdAck = args[7].toBoolean(),
                callerRuns = args[6].toBoolean(),
                connectionCount = m,
                clientLoops = minOf(args[8].toInt(), m),
                remote = targets(args[0], args[1].toInt(), args[2].toInt()),
            )
        latencyLoad("slice", m, args[4].toLong(), args[5].toLong(), { emptyList() }, loopback::clientLoopThreads, args[9].toLong()) { i, record, done ->
            runBlocking {
                val conn = loopback.connections[i]
                val request = filledRequest(64)
                if (mode == "rt") {
                    val stream = conn.openStream()
                    while (!done()) {
                        val t0 = System.nanoTime()
                        request.position = 0
                        stream.write(request)
                        stream.readExactly(64)
                        record(System.nanoTime() - t0)
                    }
                } else {
                    while (!done()) {
                        val t0 = System.nanoTime()
                        val stream = conn.openStream()
                        request.position = 0
                        stream.write(request)
                        stream.shutdownSend()
                        stream.readToEnd()
                        record(System.nanoTime() - t0)
                    }
                }
            }
        }
        println(loopback.report(1))
        Runtime.getRuntime().halt(0)
    }
}

/** args: bind host, base port. `-Dbench.nettyHandlerExecutor=true` moves the handler off the event loop. */
internal object NettyServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        com.ditchoom.socket.benchmark.NettyQuicLoopback(connectionCount = 0, clientThreads = 1, serverLoops = 1, bindHost = args[0], basePort = args[1].toInt())
        println("# netty server: handlerExecutor=${System.getProperty("bench.nettyHandlerExecutor") == "true"}")
        serveUntilKilled()
    }
}

/** args: host, base port, connections, warm-up s, measured s, client loops, start at (epoch ms), mode (rt|spr). */
internal object NettyClientMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            e.printStackTrace()
            Runtime.getRuntime().halt(1)
        }
        val m = args[2].toInt()
        val mode = args.getOrElse(7) { "rt" }
        val loopback =
            com.ditchoom.socket.benchmark.NettyQuicLoopback(m, minOf(args[5].toInt(), m), targets = targets(args[0], args[1].toInt(), 1))
        latencyLoad("netty", m, args[3].toLong(), args[4].toLong(), { emptyList() }, loopback::clientLoopThreads, args[6].toLong()) { i, record, done ->
            val request = com.ditchoom.socket.benchmark.nettyFilledRequest(64)
            if (mode == "rt") {
                val (stream, sink) = loopback.openStream(i)
                while (!done()) {
                    val t0 = System.nanoTime()
                    sink.reset()
                    stream.writeAndFlush(request.retainedDuplicate())
                    sink.awaitBytes(64)
                    record(System.nanoTime() - t0)
                }
            } else {
                while (!done()) {
                    val t0 = System.nanoTime()
                    val (stream, sink) = loopback.openStream(i)
                    stream.writeAndFlush(request.retainedDuplicate()).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                    sink.awaitFinished()
                    stream.close().awaitUninterruptibly(10, TimeUnit.SECONDS)
                    record(System.nanoTime() - t0)
                }
            }
        }
        Runtime.getRuntime().halt(0)
    }
}
