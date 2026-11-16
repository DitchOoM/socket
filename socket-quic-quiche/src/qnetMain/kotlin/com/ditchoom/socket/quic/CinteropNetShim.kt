@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.qnet.qn_close
import com.ditchoom.socket.quic.qnet.qn_poller_add
import com.ditchoom.socket.quic.qnet.qn_poller_close
import com.ditchoom.socket.quic.qnet.qn_poller_modify
import com.ditchoom.socket.quic.qnet.qn_poller_open
import com.ditchoom.socket.quic.qnet.qn_poller_ready_at
import com.ditchoom.socket.quic.qnet.qn_poller_remove
import com.ditchoom.socket.quic.qnet.qn_poller_wait
import com.ditchoom.socket.quic.qnet.qn_poller_wake
import com.ditchoom.socket.quic.qnet.qn_spec_layout
import com.ditchoom.socket.quic.qnet.qn_udp_capabilities
import com.ditchoom.socket.quic.qnet.qn_udp_error
import com.ditchoom.socket.quic.qnet.qn_udp_open_socket
import com.ditchoom.socket.quic.qnet.qn_udp_recv
import com.ditchoom.socket.quic.qnet.qn_udp_recv_coalesced
import com.ditchoom.socket.quic.qnet.qn_udp_send
import com.ditchoom.socket.quic.qnet.qn_udp_set_dont_fragment
import com.ditchoom.socket.quic.qnet.qn_udp_set_gro
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * [NetShim] on Kotlin/Native: direct calls into `qnet`, compiled into the `QNet` cinterop klib on every target.
 * On iOS it is built without Darwin's batched calls, which are outside the public SDK.
 */
internal object CinteropNetShim : NetShim {
    override val binding: NetBinding get() = NetBinding.Cinterop

    override fun pollerOpen(capacity: Int): Long = qn_poller_open(capacity)

    override fun pollerAdd(
        poller: Long,
        fd: Int,
        token: Int,
    ): Int = qn_poller_add(poller, fd, token)

    override fun pollerModify(
        poller: Long,
        fd: Int,
        token: Int,
        writable: Int,
    ): Int = qn_poller_modify(poller, fd, token, writable)

    override fun pollerRemove(
        poller: Long,
        fd: Int,
    ): Int = qn_poller_remove(poller, fd)

    override fun pollerWait(
        poller: Long,
        mode: Int,
        nanos: Long,
    ): Int = qn_poller_wait(poller, mode, nanos)

    override fun pollerReadyAt(
        poller: Long,
        index: Int,
    ): Int = qn_poller_ready_at(poller, index)

    override fun pollerWake(poller: Long): Int = qn_poller_wake(poller)

    override fun pollerClose(poller: Long) = qn_poller_close(poller)

    override fun specLayout(): Int = qn_spec_layout()

    override fun udpOpenSocket(spec: Long): Int = qn_udp_open_socket(spec)

    override fun udpCapabilities(fd: Int): Int = qn_udp_capabilities(fd)

    override fun udpSetGro(fd: Int): Int = qn_udp_set_gro(fd)

    override fun udpSetDontFragment(
        fd: Int,
        family: Int,
    ): Int = qn_udp_set_dont_fragment(fd, family)

    override fun udpSend(
        fd: Int,
        family: Int,
        mode: Int,
        base: Long,
        stride: Int,
        records: Long,
        count: Int,
        segments: Int,
    ): Long = qn_udp_send(fd, family, mode, base, stride, records, count, segments)

    override fun udpRecv(
        fd: Int,
        mode: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long = qn_udp_recv(fd, mode, records, count, size)

    override fun udpRecvCoalesced(
        fd: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long = qn_udp_recv_coalesced(fd, records, count, size)

    override fun udpError(fd: Int): Int = qn_udp_error(fd)

    override fun close(fd: Int): Int = qn_close(fd)
}
