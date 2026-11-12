package com.ditchoom.socket

import com.ditchoom.socket.nio2.AsyncServerSocket
import com.ditchoom.socket.nio2.util.LoopbackReach
import com.ditchoom.socket.nio2.util.bindEphemeralListener
import com.ditchoom.socket.nio2.util.loopbackReach
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.AsynchronousServerSocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An ephemeral listener is one IPv4 loopback clients reach. On Darwin a dual-stack listener can be handed a
 * port whose IPv4 half another socket holds, and every `127.0.0.1` connection then goes to that socket.
 * The stranger here is a plain loopback listener the candidate's proof is steered at, which is the same
 * observation on every platform.
 */
class EphemeralListenerTests {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun aListenerAConnectionReachesIsReached() =
        runBlocking {
            val listener = AsynchronousServerSocketChannel.open().bind(null, 1)
            try {
                val port = (listener.localAddress as InetSocketAddress).port
                assertEquals(LoopbackReach.Reaches, listener.loopbackReach(InetSocketAddress(loopback, port)))
            } finally {
                listener.close()
            }
        }

    @Test
    fun aConnectionAStrangerTakesIsIntercepted() =
        runBlocking {
            ServerSocket(0, 1, loopback).use { stranger ->
                val listener = AsynchronousServerSocketChannel.open().bind(null, 1)
                try {
                    val dial = InetSocketAddress(loopback, stranger.localPort)
                    assertEquals(LoopbackReach.Intercepted, listener.loopbackReach(dial))
                } finally {
                    listener.close()
                }
            }
        }

    @Test
    fun anInterceptedCandidateIsClosedAndAnotherDrawn() =
        runBlocking {
            ServerSocket(0, 1, loopback).use { stranger ->
                val candidates = mutableListOf<AsynchronousServerSocketChannel>()
                val chosen =
                    bindEphemeralListener(
                        backlog = 1,
                        bind = { AsynchronousServerSocketChannel.open().bind(null, 1).also { candidates += it } },
                        dial = { candidate ->
                            if (candidate === candidates.first()) {
                                InetSocketAddress(loopback, stranger.localPort)
                            } else {
                                InetSocketAddress(loopback, (candidate.localAddress as InetSocketAddress).port)
                            }
                        },
                    )
                try {
                    assertEquals(2, candidates.size, "the intercepted candidate was replaced by exactly one more")
                    assertFalse(candidates.first().isOpen, "the intercepted candidate was left open")
                    assertNotSame(candidates.first(), chosen)
                    assertSame(candidates.last(), chosen)
                    assertTrue(chosen.isOpen)
                } finally {
                    chosen.close()
                }
            }
        }

    @Test
    fun anEphemeralServerSocketAcceptsAnIpv4LoopbackClient() =
        runBlocking {
            val server = AsyncServerSocket()
            server.bind()
            try {
                val port = server.port()
                assertTrue(port > 0)
                val pending = java.net.Socket(loopback, port)
                pending.close()
            } finally {
                server.close()
            }
        }
}
