@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.htons
import platform.posix.ntohs
import platform.posix.recv
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IoUringUdpServerChannelTest {
    @Test
    fun recvFrom_returns_data_and_peer_address() =
        runBlocking {
            withTimeout(10.seconds) {
                memScoped {
                    // Create & bind server UDP socket
                    val serverFd = socket(AF_INET, SOCK_DGRAM, 0)
                    assertTrue(serverFd >= 0, "server socket() failed")
                    val serverAddr = alloc<sockaddr_in>()
                    serverAddr.sin_family = AF_INET.convert()
                    serverAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                    serverAddr.sin_port = htons(0u)
                    val bindRc = bind(serverFd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                    assertEquals(0, bindRc, "bind() failed")

                    // Get assigned port
                    val boundAddr = alloc<sockaddr_in>()
                    val boundLen = alloc<socklen_tVar>()
                    boundLen.value = sizeOf<sockaddr_in>().convert()
                    getsockname(serverFd, boundAddr.ptr.reinterpret(), boundLen.ptr)
                    val serverPort = ntohs(boundAddr.sin_port)
                    assertTrue(serverPort > 0u, "port not assigned")

                    val serverChannel = IoUringUdpServerChannel(serverFd)
                    try {
                        // Create sender socket
                        val senderFd = socket(AF_INET, SOCK_DGRAM, 0)
                        assertTrue(senderFd >= 0, "sender socket() failed")

                        val message = "hello from sender"
                        val msgBytes = message.encodeToByteArray()

                        // Start receiving (suspends until data arrives)
                        val factory = BufferFactory.deterministic()
                        val recvBuf = factory.allocate(1500)
                        val recvJob = async { serverChannel.recvFrom(recvBuf) }

                        // Send from client
                        val destAddr = alloc<sockaddr_in>()
                        destAddr.sin_family = AF_INET.convert()
                        destAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                        destAddr.sin_port = htons(serverPort)
                        val sent =
                            msgBytes.usePinned { pinned ->
                                sendto(
                                    senderFd,
                                    pinned.addressOf(0),
                                    msgBytes.size.convert(),
                                    0,
                                    destAddr.ptr.reinterpret<sockaddr>(),
                                    sizeOf<sockaddr_in>().convert(),
                                )
                            }
                        assertEquals(msgBytes.size.toLong(), sent, "sendto returned $sent")

                        // Verify received data
                        val result = recvJob.await()
                        assertEquals(msgBytes.size, result.bytesReceived, "wrong byte count")

                        // Read the received bytes from the buffer
                        val receivedBytes = ByteArray(result.bytesReceived)
                        val nativeAddr = recvBuf.nativeMemoryAccess!!.nativeAddress
                        for (i in receivedBytes.indices) {
                            receivedBytes[i] = (nativeAddr + i).toCPointer<ByteVar>()!!.pointed.value
                        }
                        assertEquals(message, receivedBytes.decodeToString())

                        // Verify peer address is IPv4 loopback
                        val peerSockAddr = result.peerAddr.reinterpret<sockaddr_in>().pointed
                        assertEquals(AF_INET, peerSockAddr.sin_family.toInt(), "wrong address family")

                        close(senderFd)
                        recvBuf.freeNativeMemory()
                    } finally {
                        serverChannel.close()
                    }
                }
            }
        }

    @Test
    fun sendTo_delivers_to_specific_peer() =
        runBlocking {
            withTimeout(10.seconds) {
                memScoped {
                    // Server socket
                    val serverFd = socket(AF_INET, SOCK_DGRAM, 0)
                    val serverAddr = alloc<sockaddr_in>()
                    serverAddr.sin_family = AF_INET.convert()
                    serverAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                    serverAddr.sin_port = htons(0u)
                    bind(serverFd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                    val boundAddr = alloc<sockaddr_in>()
                    val boundLen = alloc<socklen_tVar>()
                    boundLen.value = sizeOf<sockaddr_in>().convert()
                    getsockname(serverFd, boundAddr.ptr.reinterpret(), boundLen.ptr)
                    val serverPort = ntohs(boundAddr.sin_port)

                    val serverChannel = IoUringUdpServerChannel(serverFd)

                    // Client socket — bound so we can receive the reply
                    val clientFd = socket(AF_INET, SOCK_DGRAM, 0)
                    val clientAddr = alloc<sockaddr_in>()
                    clientAddr.sin_family = AF_INET.convert()
                    clientAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                    clientAddr.sin_port = htons(0u)
                    bind(clientFd, clientAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())

                    try {
                        val factory = BufferFactory.deterministic()

                        // Client sends a packet to server (so server captures peer address)
                        val msg = "ping"
                        val msgBytes = msg.encodeToByteArray()
                        val destAddr = alloc<sockaddr_in>()
                        destAddr.sin_family = AF_INET.convert()
                        destAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                        destAddr.sin_port = htons(serverPort)
                        msgBytes.usePinned { pinned ->
                            sendto(
                                clientFd,
                                pinned.addressOf(0),
                                msgBytes.size.convert(),
                                0,
                                destAddr.ptr.reinterpret<sockaddr>(),
                                sizeOf<sockaddr_in>().convert(),
                            )
                        }

                        // Server receives — captures peer address
                        val recvBuf = factory.allocate(1500)
                        val result = serverChannel.recvFrom(recvBuf)
                        assertEquals(msgBytes.size, result.bytesReceived)

                        // Server sends reply to the captured peer
                        val reply = "pong"
                        val replyBytes = reply.encodeToByteArray()
                        val replyBuf = factory.allocate(replyBytes.size)
                        val replyAddr = replyBuf.nativeMemoryAccess!!.nativeAddress
                        for (i in replyBytes.indices) {
                            (replyAddr + i).toCPointer<ByteVar>()!!.pointed.value = replyBytes[i]
                        }
                        serverChannel.sendTo(replyBuf, replyBytes.size, result.peerAddr, result.peerAddrLen)

                        // Client receives the reply
                        val clientRecvBuf = ByteArray(1500)
                        val received =
                            clientRecvBuf.usePinned { pinned ->
                                recv(clientFd, pinned.addressOf(0), 1500u.convert(), 0)
                            }
                        assertEquals(replyBytes.size.toLong(), received)
                        assertEquals(reply, clientRecvBuf.decodeToString(0, received.toInt()))

                        recvBuf.freeNativeMemory()
                        replyBuf.freeNativeMemory()
                    } finally {
                        close(clientFd)
                        serverChannel.close()
                    }
                }
            }
        }
}
