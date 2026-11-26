package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget

/**
 * The send half of [NativeIo]: a batch's lengths, targets and reply sources go into its lane's native records
 * ([sendRecords], one per lane by [SendBatch.lane]) and `qnet` is handed the batch's own memory, one call per run of
 * datagrams for the same socket. Safe from any thread, one
 * thread per lane. A device that refuses segmented sends steps [mode] down to [BatchCapability.Multi] for good; a link
 * whose MTU is below a datagram size (EMSGSIZE) sends that batch without segments, and takes segments only for smaller
 * datagrams after ([segmentLimit]).
 */
internal class NativeSend(
    private val shim: NetShim,
    private val socket: NativeSocket,
    private val sendRecords: List<NativeBytes>,
    private val mode: CapabilityCell,
) {
    private val segmentLimit = SegmentLimit()

    fun send(batch: SendBatch): SendBatchResult {
        val count = batch.pending
        val records = sendRecords[batch.lane]
        var longest = 0
        for (n in 0 until count) {
            val at = n * NativeIo.SEND_RECORD
            val length = batch.lengthOf(n)
            if (length > longest) longest = length
            records.putInt(at, length)
            when (val target = batch.targetOf(n)) {
                is SendTarget.ConnectedPeer -> {
                    records.putInt(at + TARGET, 0)
                    records.putInt(at + NativeIo.SEND_SOURCE, 0)
                }
                is SendTarget.ServerReply -> {
                    putRecord(records, at + TARGET, target.to)
                    when (val source = target.from) {
                        is ReplySource.Recorded -> putRecord(records, at + NativeIo.SEND_SOURCE, source.key)
                        is ReplySource.Undecodable -> records.putInt(at + NativeIo.SEND_SOURCE, 0)
                    }
                }
            }
        }
        var taken = 0
        while (taken < count) {
            val run = batch.runFrom(taken)
            val pair = socket.pair()
            val ticket = batch.socketOf(taken)
            if (ticket == pair.carrying()) {
                when (val sent = sendRun(batch, records, taken, run, longest, pair.cell(), pair.family())) {
                    is SendBatchResult.AllTaken -> taken += run
                    is SendBatchResult.Partial -> return SendBatchResult.Partial(taken + sent.taken, sent.why)
                }
            } else {
                // A datagram for the second socket is best effort, and one for a socket that is gone goes nowhere.
                when (pair) {
                    is NativePair.Two ->
                        if (pair.second.ticket == ticket) {
                            sendRun(batch, records, taken, run, longest, pair.second.cell, pair.second.family)
                        }
                    is NativePair.One -> Unit
                }
                taken += run
            }
        }
        return SendBatchResult.AllTaken
    }

    /** Sends the [count] datagrams from the [skip]th on through [cell]'s descriptor, of [family]. */
    private fun sendRun(
        batch: SendBatch,
        records: NativeBytes,
        skip: Int,
        count: Int,
        longest: Int,
        cell: DescriptorCell,
        family: Int,
    ): SendBatchResult {
        var taken = 0
        while (true) {
            val capability = mode.load()
            val segmented = capability is BatchCapability.Segmented && capability.send is Gso.On && segmentLimit.allows(longest)
            val result =
                sendFrom(batch, records, skip + taken, count - taken, if (segmented) capability else unsegmented(capability), cell, family)
            if (result and NativeResult.SEGMENT_REFUSED != 0L) mode.stepDown(capability)
            taken += NativeResult.count(result)
            val code = NativeResult.code(result)
            if (code == 0) return SendBatchResult.AllTaken
            val error = NativeResult.errorOf(code)
            if (segmented && error is UdpError.MessageTooLarge) {
                // The link's MTU is below this datagram size: send the rest without segments, and remember the size.
                segmentLimit.refuse(longest)
                continue
            }
            return SendBatchResult.Partial(taken, error)
        }
    }

    private fun sendFrom(
        batch: SendBatch,
        records: NativeBytes,
        skip: Int,
        count: Int,
        capability: BatchCapability,
        cell: DescriptorCell,
        family: Int,
    ): Long =
        cell.use(
            call = { fd ->
                shim.udpSend(
                    fd,
                    family,
                    modeOf(capability),
                    batch.storage.address + batch.offsetOf(skip),
                    batch.limits.maxDatagramSize,
                    records.address + skip * NativeIo.SEND_RECORD,
                    count,
                    segmentsOf(capability),
                )
            },
            replacing = { NativeResult.REPLACED },
            // A cell no longer the socket's carrying one, or second, was replaced under this sender: its datagrams drop.
            closed = { if (cell === socket.pair().cell()) NativeResult.CLOSED else NativeResult.REPLACED },
        )

    private companion object {
        const val TARGET = 8
    }
}

/** [capability] without segments: the same socket's message-per-datagram batching. */
private fun unsegmented(capability: BatchCapability): BatchCapability =
    when (capability) {
        is BatchCapability.Segmented -> BatchCapability.Multi(capability.maxDatagrams)
        is BatchCapability.Multi, is BatchCapability.OneByOne -> capability
    }

/** `QN_IO_*`: how `qnet` sends or receives under [capability]. */
internal fun modeOf(capability: BatchCapability): Int =
    when (capability) {
        is BatchCapability.OneByOne -> 0
        is BatchCapability.Multi -> 1
        is BatchCapability.Segmented ->
            when (capability.send) {
                is Gso.On -> 2
                is Gso.Off -> 1
            }
    }

private fun segmentsOf(capability: BatchCapability): Int =
    when (capability) {
        is BatchCapability.OneByOne, is BatchCapability.Multi -> 1
        is BatchCapability.Segmented ->
            when (val gso = capability.send) {
                is Gso.On -> gso.maxSegments
                is Gso.Off -> 1
            }
    }
