package com.ditchoom.socket.quic.core.testing

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.ReadyTokens
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WakeCause
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.network

/** A [Poller] that only counts the wakes it is asked for. */
internal class FakePoller : Poller {
    var wakes = 0
        private set

    override fun add(
        socket: SocketRef,
        token: Int,
    ) = Unit

    override fun remove(token: Int) = Unit

    override fun interest(
        token: Int,
        write: WriteInterest,
    ) = Unit

    override fun await(
        until: NextDue,
        ready: ReadyTokens,
        now: Nanos,
    ): WakeCause = WakeCause.Woken

    override fun wake() {
        wakes++
    }

    override fun close() = Unit
}

/** Time that moves only when a test says so; counts how often it was read. */
internal class VirtualClock(
    var at: Nanos = Nanos(0L),
    /** What its readings count on: a clock of its own unless a test stands it in for libquiche's. */
    private val base: ClockBase = ClockBase.Own,
) : LoopClock {
    var reads = 0L

    override fun now(): Nanos {
        reads++
        return at
    }

    override fun base(): ClockBase = base
}

/** A [DatagramBatchIo] whose answer to each send is scripted by the test; it never takes a datagram itself. */
internal class FakeIo : DatagramBatchIo {
    var onSend: (pending: Int) -> SendBatchResult = { SendBatchResult.AllTaken }
    var probeAnswer = SocketProbe.Open
    var sendCalls = 0
        private set

    override val capability: BatchCapability = BatchCapability.OneByOne

    override fun send(batch: SendBatch): SendBatchResult {
        sendCalls++
        return onSend(batch.pending)
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult = ReceiveBatchResult.Nothing

    override fun probe(): SocketProbe = probeAnswer

    /** What [reopen] answers; the test may change it. */
    var reopenAnswer: Reopened = Reopened.SameEndpoint
    var reopens = 0
        private set

    override fun reopen(): Reopened {
        reopens++
        return reopenAnswer
    }

    /** What [openBeside] answers; the test may change it. */
    var besideAnswer: Beside = Beside.Unsupported
    val movedTo = ArrayList<PathKey>()

    /** The ticket each [openBeside] named, in order. */
    val openedAs = ArrayList<SocketTicket>()

    /** What [settleBeside] answers while a socket is made; the test may change it. */
    var settleAnswer: Beside = Beside.Unsupported

    /** How many times the second socket, open or being made, was closed. */
    var secondsClosed = 0
        private set

    override fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside {
        movedTo += bind
        openedAs += ticket
        return besideAnswer
    }

    override fun settleBeside(): Beside = settleAnswer

    override fun closeSecond() {
        secondsClosed++
    }

    /** What [receiveSecond] answers; the test may change it. */
    var secondReceiveAnswer: ReceiveBatchResult = ReceiveBatchResult.Nothing

    override fun receiveSecond(into: ReceiveBatch): ReceiveBatchResult = secondReceiveAnswer

    /** What [reopenSecond] answers; the test may change it. */
    var reopenSecondAnswer: Reopened = Reopened.SameEndpoint
    var secondReopens = 0
        private set

    override fun reopenSecond(): Reopened {
        secondReopens++
        return reopenSecondAnswer
    }

    var promotes = 0
        private set

    override fun promote() {
        promotes++
    }

    override fun close(): SocketClosed = SocketClosed
}

/** Loop work that records what happened to it. */
internal class RecordingWork : LoopWork() {
    var runs = 0
        private set
    val goneWith = ArrayList<ConnEnd>()

    override fun run(port: LoopPort) {
        runs++
    }

    override fun gone(end: ConnEnd) {
        goneWith.add(end)
    }
}

internal val Limits = BatchLimits(maxDatagrams = 8, maxDatagramSize = 1200)

/** Native memory for [bytes] bytes, zeroed: the network factory pools, so a buffer can hold an earlier test's bytes. */
internal fun nativeBytes(bytes: Int): NativeBytes =
    when (val a = NativeBytes.of(BufferFactory.network().allocate(bytes))) {
        is Addressed.Native -> a.bytes.also { UnsafeMemory.setMemory(it.address, bytes.toLong(), 0) }
        is Addressed.NotAddressable -> error("the network factory makes native memory")
    }

/** A batch with [datagrams] datagrams of 100 bytes already committed. */
internal fun batchWith(
    datagrams: Int,
    lane: Int = 0,
): SendBatch {
    val batch = SendBatch(nativeBytes(Limits.maxDatagrams * Limits.maxDatagramSize), Limits, lane)
    repeat(datagrams) { batch.commit(100, SendTarget.ConnectedPeer) }
    return batch
}
