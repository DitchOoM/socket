@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.LentDrop
import com.ditchoom.socket.quic.core.udp.LentDrops
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A lent io's run: not started, pumping, or closed. */
private sealed interface LentRun {
    data object Unstarted : LentRun

    class Running(
        val pump: LentPump,
    ) : LentRun

    data object Closed : LentRun
}

/**
 * The [DatagramBatchIo] of a channel the caller lent ([LentChannel]): one socket, which no poller watches and which this
 * io never closes. Nothing runs until its endpoint joins the loop: then the io takes its channel's claim from the
 * connect or serve that made it and starts its [LentPump]. The loop copies each arrival into its receive buffer, and
 * each datagram it sends into a slot of the pump's outbox; with every slot taken the rest wait in the batch, and the
 * pump wakes the loop when one frees. Only the loop sends: a connection on a lent channel lends its callers no lane.
 * The loop's side allocates nothing per datagram. [close] stops the pump, or, for an io that never started, gives the
 * claim back.
 */
@Queue
internal class LentIo(
    private val channel: LentChannel,
    private val peers: LentPeers,
    private val factory: BufferFactory,
    val drops: LentDrops,
    /** Any thread: the loop has news to read. */
    private val wake: () -> Unit,
) : DatagramBatchIo {
    private val book = PeerBook(channel.local)
    private val run = AtomicReference<LentRun>(LentRun.Unstarted)

    override val capability: BatchCapability = BatchCapability.OneByOne

    /** The endpoint joined its loop: the pump starts, before the loop first reads or sends. */
    override fun placed() {
        running()
    }

    override fun send(batch: SendBatch): SendBatchResult {
        val pump =
            when (val now = running()) {
                is LentRun.Running -> now.pump
                is LentRun.Unstarted, is LentRun.Closed -> return SendBatchResult.Partial(0, UdpError.SocketClosed)
            }
        val outbox = pump.outbox
        var taken = 0
        while (taken < batch.pending && outbox.hasRoom()) {
            when (peers) {
                // A connected client has one peer, whatever its batch names, as a connected socket of the loop's ignores it.
                is LentPeers.One -> offer(outbox, batch, taken, peers.sendTo)
                is LentPeers.Every ->
                    when (val target = batch.targetOf(taken)) {
                        is SendTarget.ServerReply -> offer(outbox, batch, taken, book.addressOf(target.to))
                        is SendTarget.ConnectedPeer -> drops.dropped(LentDrop.Unaddressed)
                    }
            }
            taken++
        }
        if (taken > 0) pump.offered()
        if (taken == batch.pending) return SendBatchResult.AllTaken
        pump.full()
        return SendBatchResult.Partial(taken, UdpError.WouldBlock)
    }

    private fun offer(
        outbox: LentRing,
        batch: SendBatch,
        n: Int,
        to: SocketAddress,
    ) {
        val length = batch.lengthOf(n)
        outbox.next().copyFrom(batch.storage, batch.offsetOf(n), 0, length)
        outbox.publish(length, to)
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        val pump =
            when (val now = running()) {
                is LentRun.Running -> now.pump
                is LentRun.Unstarted, is LentRun.Closed -> return ReceiveBatchResult.Failed(UdpError.SocketClosed)
            }
        pump.reading()
        val arrivals = pump.arrivals
        var got = 0
        while (!arrivals.isEmpty()) {
            val buffer = into.takeToCopyInto()
            // Only a full batch has no buffer: what is left is read in the same pass, after this batch is delivered.
            if (buffer !is ReceiveBuffer) return ReceiveBatchResult.GotWithBacklog
            val length = arrivals.headLength()
            buffer.bytes.copyFrom(arrivals.head(), 0, 0, length)
            into.received(buffer, length, keyOf(arrivals.headAddress()), ArrivedOn.Unreported)
            arrivals.pop()
            got++
        }
        if (got > 0) return ReceiveBatchResult.Got
        return when (val ended = pump.end()) {
            is LentEnd.Open -> ReceiveBatchResult.Nothing
            is LentEnd.Closed -> ReceiveBatchResult.Failed(UdpError.SocketClosed)
            is LentEnd.Threw -> ReceiveBatchResult.Failed(UdpError.Unnamed(ended.thrown))
        }
    }

    /** Loop thread: a client's datagrams all come from its peer; a server's sources are looked up once each. */
    private fun keyOf(from: SocketAddress): PathKey =
        when (peers) {
            is LentPeers.One -> peers.key
            is LentPeers.Every -> book.keyOf(from)
        }

    override fun probe(): SocketProbe =
        when (val now = run.load()) {
            is LentRun.Unstarted -> SocketProbe.Open
            is LentRun.Running ->
                when (now.pump.end()) {
                    is LentEnd.Open -> SocketProbe.Open
                    is LentEnd.Closed, is LentEnd.Threw -> SocketProbe.Closed
                }
            is LentRun.Closed -> SocketProbe.Closed
        }

    /** The owner's socket is the owner's to follow the network with: it is the same endpoint. */
    override fun reopen(): Reopened = Reopened.SameEndpoint

    /** Ends this io's use of the channel, which stays open; one that never started only gives its claim back. */
    override fun close(): SocketClosed {
        when (val was = run.exchange(LentRun.Closed)) {
            is LentRun.Unstarted -> channel.claim.ioDone()
            is LentRun.Running -> was.pump.close()
            is LentRun.Closed -> Unit
        }
        return SocketClosed
    }

    /** Tests: whether the io started its pump and the pump's coroutines have ended. */
    internal fun pumpEnded(): Boolean =
        when (val now = run.load()) {
            is LentRun.Running -> now.pump.ended()
            is LentRun.Unstarted, is LentRun.Closed -> false
        }

    /** Loop thread: the run, started on the first read or send, once the io holds its channel's claim. */
    private fun running(): LentRun {
        val now = run.load()
        if (now !is LentRun.Unstarted) return now
        return when (channel.claim.takeByIo()) {
            ClaimTaken.AlreadyReleased -> LentRun.Closed.also { run.store(it) }
            ClaimTaken.ByIo -> {
                val started = LentRun.Running(LentPump(channel, peers, factory, drops, wake))
                if (run.compareAndSet(now, started)) started else run.load().also { started.pump.close() }
            }
        }
    }
}
