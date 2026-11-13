package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.benchmark.slice.latencyLoad
import com.ditchoom.socket.benchmark.slice.serveUntilKilled
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours

/* This library's server alone, and its load from another process: the twins of the slice's split programs. */

/** args: bind host, port. */
internal object LibServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        thread(name = "lib-server", isDaemon = true) {
            runBlocking(Dispatchers.IO) {
                withQuicServer(port = args[1].toInt(), host = args[0], tlsConfig = loopbackTls(), quicOptions = QuicLoopback.OPTIONS) {
                    launch { connections { sinkOrEchoForLoad() } }
                    awaitCancellation()
                }
            }
        }
        println("# lib server: backend=${assertQuicheBackend()}")
        Thread.sleep(500)
        serveUntilKilled()
    }
}

/** args: host, port, connections, warm-up s, measured s, start at (epoch ms), mode (rt|spr). */
internal object LibClientMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val m = args[2].toInt()
        val mode = args.getOrElse(6) { "rt" }
        val stop = CompletableDeferred<Unit>()
        val clients =
            List(m) { i ->
                val ready = CompletableDeferred<QuicScope>()
                thread(name = "load-conn-$i", isDaemon = true) {
                    try {
                        runBlocking(Dispatchers.IO) {
                            withQuicConnection(args[0], args[1].toInt(), QuicLoopback.OPTIONS, timeout = 24.hours) {
                                ready.complete(this)
                                stop.await()
                            }
                        }
                    } catch (t: Throwable) {
                        ready.completeExceptionally(t)
                    }
                }
                runBlocking { ready.await() }
            }
        latencyLoad("lib", m, args[3].toLong(), args[4].toLong(), { emptyList() }, { emptyList() }, args[5].toLong()) { i, record, done ->
            runBlocking {
                val scope = clients[i]
                val req = scope.bufferFactory.allocate(64)
                req.writeByte(ECHO_MARKER)
                repeat(63) { req.writeByte('r'.code.toByte()) }
                req.resetForRead()
                if (mode == "rt") {
                    val stream = scope.openStream()
                    while (!done()) {
                        val t0 = System.nanoTime()
                        req.position(0)
                        stream.writeFully(req, IO_TIMEOUT)
                        stream.readExactly(64)
                        record(System.nanoTime() - t0)
                    }
                } else {
                    while (!done()) {
                        val t0 = System.nanoTime()
                        val stream = scope.openStream()
                        try {
                            req.position(0)
                            stream.writeFully(req, IO_TIMEOUT)
                            stream.shutdownSend()
                            stream.readToEnd()
                        } finally {
                            stream.close()
                        }
                        record(System.nanoTime() - t0)
                    }
                }
            }
        }
        Runtime.getRuntime().halt(0)
    }
}
