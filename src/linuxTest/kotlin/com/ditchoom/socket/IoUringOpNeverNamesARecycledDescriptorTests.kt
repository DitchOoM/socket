package com.ditchoom.socket

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.data.writeString
import com.ditchoom.socket.linux.io_uring_prep_nop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AF_UNIX
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFD
import platform.posix.F_SETFL
import platform.posix.INADDR_LOOPBACK
import platform.posix.MSG_DONTWAIT
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ACCEPTCONN
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.dup2
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socketpair
import platform.posix.socklen_tVar
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * An io_uring operation names its socket's descriptor by NUMBER, and the poller thread builds the SQE from
 * that number only when it drains the operation — after a channel hand-off and a poller iteration. A close
 * that frees the number before then lets the kernel resolve it to whatever the process opened next: a read
 * consumes another connection's bytes, a write sends ours down another connection, an accept takes another
 * listener's client. Each test queues one operation behind a poller held inside a `prepareOp`, closes the
 * socket, and — if the close freed the number — installs another socket on it before letting the poller go.
 * The operation must end as closed, and the other socket must be untouched.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringOpNeverNamesARecycledDescriptorTests {
    @Test
    fun aReadQueuedWhenItsClientClosesNeverReadsTheSocketThatTookItsNumber() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            withConnection { client, _ ->
                val number = descriptorOf(local = client.localPort(), peer = client.remotePort())
                assertAReadQueuedAtCloseEndsClosed(number, read = { client.read(10.seconds) }, close = { client.close() })
            }
        }

    @Test
    fun aReadQueuedWhenItsAcceptedSocketClosesNeverReadsTheSocketThatTookItsNumber() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            withConnection { client, serverSide ->
                val number = descriptorOf(local = client.remotePort(), peer = client.localPort())
                assertAReadQueuedAtCloseEndsClosed(
                    number,
                    read = { serverSide.read(10.seconds) },
                    close = { serverSide.close() },
                )
            }
        }

    @Test
    fun aWriteQueuedWhenItsClientClosesNeverSendsDownTheSocketThatTookItsNumber() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            withConnection { client, _ ->
                val number = descriptorOf(local = client.localPort(), peer = client.remotePort())
                val hold = PollerHold.engage()
                val (impostor, write) =
                    try {
                        val write =
                            async(Dispatchers.Default) { runCatching { client.writeString(LEAKED, deadline = 10.seconds) } }
                        awaitQueuedBehindTheHold()
                        client.close()
                        Impostor.takeIfFree(number) to write
                    } finally {
                        hold.release()
                    }
                val outcome = write.await()
                try {
                    impostor?.assertNothingWasSentToIt()
                    val failure = outcome.exceptionOrNull()
                    assertTrue(
                        failure is SocketClosedException,
                        "a write queued when its socket closed must fail as closed, but " +
                            (failure?.let { "threw $it" } ?: "reported success") + reused(impostor, number),
                    )
                } finally {
                    impostor?.close()
                }
            }
        }

    @Test
    fun anAcceptQueuedWhenItsServerClosesNeverTakesAnotherListenersClient() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            // Keeps the open-socket count above zero: a last close asks the held poller to release its ring
            // and would wait on it.
            val keeper = ServerSocket.allocate()
            keeper.bind()
            val server = ServerSocket.allocate()
            val connections = server.bind()
            val number = listenerDescriptorOf(server.port())
            val emitted = Channel<ClientSocket>(Channel.UNLIMITED)
            val hold = PollerHold.engage()
            val (other, collector) =
                try {
                    val collector = launch(Dispatchers.Default) { connections.collect { emitted.send(it) } }
                    awaitQueuedBehindTheHold()
                    server.close()
                    OtherListener.takeIfFree(number) to collector
                } finally {
                    hold.release()
                }
            try {
                withTimeout(10.seconds) { collector.join() }
                val stolen = emitted.tryReceive().getOrNull()
                if (stolen != null) {
                    stolen.close()
                    fail(
                        "an accept queued when its server closed accepted a connection from descriptor $number " +
                            "after the close" + (if (other != null) " — another listener had taken that number" else ""),
                    )
                }
                other?.assertItsClientIsStillWaiting()
            } finally {
                collector.cancel()
                other?.close()
                keeper.close()
            }
        }

    private suspend fun CoroutineScope.assertAReadQueuedAtCloseEndsClosed(
        number: Int,
        read: suspend () -> ReadResult,
        close: suspend () -> Unit,
    ) {
        val hold = PollerHold.engage()
        val (impostor, pending) =
            try {
                val pending = async(Dispatchers.Default) { read() }
                awaitQueuedBehindTheHold()
                close()
                Impostor.takeIfFree(number) to pending
            } finally {
                hold.release()
            }
        val result = pending.await()
        try {
            if (result is ReadResult.Data) {
                val stolen = result.buffer.remaining()
                result.buffer.freeIfNeeded()
                fail(
                    "a read queued when its socket closed read $stolen byte(s) from descriptor $number after the " +
                        "close" + reused(impostor, number),
                )
            }
            assertEquals(ReadResult.End, result, "a read queued when its socket closed must end as closed")
            impostor?.assertItsBytesAreUnread()
        } finally {
            impostor?.close()
        }
    }

    /** A connected client and its accepted socket, both closed after [body]. */
    private suspend fun withConnection(body: suspend CoroutineScope.(client: ClientSocket, serverSide: ClientSocket) -> Unit) =
        coroutineScope {
            val server = ServerSocket.allocate()
            val accepted = Channel<ClientSocket>(Channel.UNLIMITED)
            val connections = server.bind()
            val acceptJob = launch(Dispatchers.Default) { connections.collect { accepted.send(it) } }
            val client = ClientSocket.allocate()
            try {
                client.open(server.port(), "127.0.0.1")
                val serverSide = withTimeout(5.seconds) { accepted.receive() }
                // No further accept may be queued: the operation under test must be the one behind the hold.
                acceptJob.cancel()
                acceptJob.join()
                try {
                    body(client, serverSide)
                } finally {
                    serverSide.close()
                }
            } finally {
                client.close()
                acceptJob.cancel()
                server.close()
            }
        }

    /** The poller thread, parked inside a `prepareOp` until [release]: whatever is sent meanwhile waits behind it. */
    private class PollerHold private constructor(
        private val released: AtomicInt,
    ) {
        fun release() {
            released.value = 1
        }

        companion object {
            suspend fun engage(): PollerHold {
                // A submission is dropped while no loop runs; this starts one.
                IoUringManager.registerOperation()
                val entered = AtomicInt(0)
                val released = AtomicInt(0)
                IoUringManager.submitNoWaitUnsafe { sqe ->
                    entered.value = 1
                    while (released.value == 0) usleep(200u)
                    io_uring_prep_nop(sqe)
                }
                waitUntil("the poller to enter the hold") { entered.value == 1 }
                return PollerHold(released)
            }
        }
    }

    /** A socket installed on a descriptor number its owner's close freed, with bytes waiting to be read. */
    private class Impostor(
        val number: Int,
        private val peer: Int,
    ) {
        fun assertItsBytesAreUnread() {
            assertEquals(
                MARK.length,
                receiveNow(number),
                "the socket that took descriptor $number must still hold the ${MARK.length} byte(s) written to it",
            )
        }

        fun assertNothingWasSentToIt() {
            val received = receiveNow(peer)
            assertTrue(received < 0, "the socket that took descriptor $number received $received byte(s) a closed client queued")
        }

        fun close() {
            close(number)
            close(peer)
        }

        companion object {
            /** Takes [number] if the close freed it; null while the number is still held, so nothing can take it. */
            fun takeIfFree(number: Int): Impostor? {
                if (fcntl(number, F_GETFD) != -1) return null
                memScoped {
                    val fds = allocArray<IntVar>(2)
                    check(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0) { "socketpair failed: errno $errno" }
                    moveOnto(fds[0], number)
                    val mark = MARK.encodeToByteArray()
                    check(send(fds[1], mark.refTo(0), mark.size.convert(), 0) == mark.size.toLong())
                    return Impostor(number, fds[1])
                }
            }
        }
    }

    /** Another listener installed on a number its server's close freed, with a client waiting to be accepted. */
    private class OtherListener(
        private val number: Int,
        private val client: Int,
    ) {
        fun assertItsClientIsStillWaiting() {
            val fd = accept(number, null, null)
            assertTrue(fd >= 0, "the listener that took descriptor $number must still have its client waiting")
            close(fd)
        }

        fun close() {
            close(client)
            close(number)
        }

        companion object {
            /**
             * Takes [number] if the close freed it. Plain POSIX throughout: a library socket would submit to the
             * held poller and wait on it.
             */
            fun takeIfFree(number: Int): OtherListener? {
                if (fcntl(number, F_GETFD) != -1) return null
                val listener = socket(AF_INET, SOCK_STREAM, 0)
                check(listener >= 0) { "socket failed: errno $errno" }
                memScoped {
                    val addr = alloc<sockaddr_in>()
                    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                    addr.sin_family = AF_INET.convert()
                    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK.convert())
                    addr.sin_port = htons(0.convert())
                    check(bind(listener, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0)
                    check(listen(listener, 4) == 0)
                    fcntl(listener, F_SETFL, O_NONBLOCK)
                    moveOnto(listener, number)
                    addr.sin_port = htons(getLocalPort(number).toUShort())
                    val client = socket(AF_INET, SOCK_STREAM, 0)
                    check(connect(client, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
                        "connect to the other listener failed: errno $errno"
                    }
                    return OtherListener(number, client)
                }
            }
        }
    }

    private companion object {
        const val MARK = "another connection's bytes"
        const val LEAKED = "a closed client's bytes"
        const val DESCRIPTOR_SCAN = 4096

        /**
         * Puts [fd] at [number]. A new descriptor takes the lowest free number, which is often [number] itself;
         * closing the original then would close the only copy.
         */
        fun moveOnto(
            fd: Int,
            number: Int,
        ) {
            if (fd == number) return
            check(dup2(fd, number) == number) { "dup2 onto $number failed: errno $errno" }
            close(fd)
        }

        fun reused(
            impostor: Impostor?,
            number: Int,
        ): String = if (impostor != null) " — another socket had taken descriptor $number" else ""

        /** Bytes waiting on [fd] without blocking, or a negative result when none are. */
        fun receiveNow(fd: Int): Int {
            val scratch = ByteArray(256)
            val n = recv(fd, scratch.refTo(0), scratch.size.convert(), MSG_DONTWAIT).toInt()
            if (n < 0) check(errno == EAGAIN || errno == EWOULDBLOCK) { "recv on $fd failed: errno $errno" }
            return n
        }

        /** The descriptor of the TCP socket whose local and peer ports are these. */
        fun descriptorOf(
            local: Int,
            peer: Int,
        ): Int =
            (0 until DESCRIPTOR_SCAN).firstOrNull { fd ->
                fcntl(fd, F_GETFD) != -1 && getLocalPort(fd) == local && getRemotePort(fd) == peer
            } ?: fail("no descriptor has local port $local and peer port $peer")

        /** The descriptor of the listening socket bound to [port]. */
        fun listenerDescriptorOf(port: Int): Int =
            (0 until DESCRIPTOR_SCAN).firstOrNull { fd ->
                fcntl(fd, F_GETFD) != -1 && getLocalPort(fd) == port && isListening(fd)
            } ?: fail("no listening descriptor is bound to port $port")

        fun isListening(fd: Int): Boolean =
            memScoped {
                val value = alloc<IntVar>()
                val length = alloc<socklen_tVar>()
                length.value = sizeOf<IntVar>().convert()
                getsockopt(fd, SOL_SOCKET, SO_ACCEPTCONN, value.ptr, length.ptr) == 0 && value.value != 0
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun awaitQueuedBehindTheHold() =
            waitUntil("the operation to queue behind the held poller") {
                (IoUringManager.pollerState as? PollerState.Running)?.queue?.isEmpty == false
            }

        suspend fun waitUntil(
            what: String,
            condition: () -> Boolean,
        ) {
            try {
                withTimeout(5.seconds) { while (!condition()) delay(1) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("timed out waiting for $what", e)
            }
        }
    }
}
