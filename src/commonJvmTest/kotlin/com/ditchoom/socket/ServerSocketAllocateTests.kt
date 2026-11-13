package com.ditchoom.socket

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.nio.NioServerSocket
import com.ditchoom.socket.nio.SelectorServerSocket
import com.ditchoom.socket.nio2.AsyncServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Which listener `ServerSocket.allocate` returns, per runtime and flag combination, and how the non-blocking
 * plain-NIO listener behaves. `useAsyncChannels` and `useNioBlocking` choose the *client*; a listener is NIO2
 * wherever NIO2 exists (every JVM, Android from API 26), and plain NIO without it (Android 23-25).
 */
class ServerSocketAllocateTests {
    private var savedAsync = true
    private var savedBlocking = false

    @BeforeTest
    fun save() {
        savedAsync = useAsyncChannels
        savedBlocking = useNioBlocking
    }

    @AfterTest
    fun restore() {
        useAsyncChannels = savedAsync
        useNioBlocking = savedBlocking
    }

    @Test
    fun withNio2TheListenerIsNio2WhateverTheClientFlagsSay() {
        for (async in listOf(true, false)) {
            for (blocking in listOf(true, false)) {
                useAsyncChannels = async
                useNioBlocking = blocking
                assertIs<AsyncServerSocket>(ServerSocket.allocate(), "async=$async blocking=$blocking")
                assertIs<AsyncServerSocket>(serverSocketFor(TransportConfig(), ServerBacking.Nio2))
            }
        }
    }

    @Test
    fun withoutNio2TheListenerIsPlainNio() {
        assertIs<SelectorServerSocket>(serverSocketFor(TransportConfig(), ServerBacking.NioSelector))
        assertIs<NioServerSocket>(serverSocketFor(TransportConfig(), ServerBacking.NioBlocking))
    }

    @Test
    fun aNonBlockingListenerRoundTripsThroughTheLoops() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val server = SelectorServerSocket()
                val accepted = server.bind(0, "127.0.0.1")
                val echo =
                    launch {
                        val client = accepted.first()
                        try {
                            client.writeString(client.readString())
                        } finally {
                            client.close()
                        }
                    }
                val client = ClientSocket.connect(server.port(), "127.0.0.1")
                try {
                    client.writeString("hello")
                    assertEquals("hello", client.readString())
                } finally {
                    client.close()
                }
                echo.join()
                server.close()
            }
        }

    @Test
    fun closingAListenerEndsItsAcceptFlow() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val server = SelectorServerSocket()
                val flow = server.bind(0, "127.0.0.1")
                val ended = CompletableDeferred<Unit>()
                val collector =
                    launch {
                        flow.collect { }
                        ended.complete(Unit)
                    }
                delay(200)
                server.close()
                ended.await()
                collector.join()
                assertTrue(!server.isListening())
            }
        }

    @Test
    fun aCancelledCollectorClosesTheListener() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val server = SelectorServerSocket()
                val flow = server.bind(0, "127.0.0.1")
                val port = server.port()
                val collector = launch { flow.collect { } }
                delay(200)
                collector.cancel()
                collector.join()
                var refused = false
                repeat(50) {
                    if (!refused) {
                        try {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                            delay(50)
                        } catch (_: ConnectException) {
                            refused = true
                        }
                    }
                }
                assertTrue(refused, "the listener still accepted connections after its collector was cancelled")
            }
        }
}
