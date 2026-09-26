@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.EADDRNOTAVAIL
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.concurrent.AtomicReference

/**
 * How a Darwin POSIX UDP socket leaves from the source a send names ([com.ditchoom.buffer.flow.DatagramSendOptions.fromLocal]).
 *
 * The source rides a control message: `IP_PKTINFO` (`ipi_spec_dst`) for an IPv4 destination —
 * including an IPv4-mapped one on a dual-stack socket, where an `IPV6_PKTINFO` is ignored — and
 * `IPV6_PKTINFO` for an IPv6 one. What the kernel checks differs by family, and that decides what this
 * has to check itself:
 *
 *  - **IPv6**: the kernel refuses a source the host does not hold (`EADDRNOTAVAIL`) on every send.
 *  - **IPv4**: the kernel checks the source only when it looks a route up. A socket that has already
 *    sent to the destination reuses its cached route and sends from any `ipi_spec_dst` at all — an
 *    address no interface holds leaves the host as a spoofed datagram and the send reports success.
 *    So an IPv4 source is checked here, once per address per socket, before it is sent from.
 *
 * Checking once is enough because the kernel discards a socket's cached route on any routing change,
 * and an address leaving the host is one; the next send then looks a route up and the kernel's own
 * check applies again.
 */
internal class DarwinSourcePinning {
    /** IPv4 sources this socket has seen the host hold, as their 32 bits. Copy-on-write; see [verify]. */
    private val heldIpv4 = AtomicReference<Set<Long>>(emptySet())

    /** What a send must do about its named [fromLocal] when it goes to [to]; throws the refusals. */
    fun pinFor(
        fromLocal: SocketAddress?,
        to: SocketAddress,
    ): SourcePin {
        if (fromLocal == null) return SourcePin.OsRouting
        val source = fromLocal.asAppleAddress().ip()
        // 0.0.0.0, ::, ::ffff:0.0.0.0 name no address: the caller left the choice to the kernel.
        if (source.isUnspecified()) return SourcePin.OsRouting
        return when (to.asAppleAddress().ip()) {
            is Ip.V4 -> (source as? Ip.V4)?.let { SourcePin.Ipv4(fromLocal.host, it.bits) }
            is Ip.V6 -> (source as? Ip.V6)?.let { SourcePin.Ipv6(fromLocal.host, it.hi, it.lo) }
        } ?: throw refusal(fromLocal.host, SourceAddressRejection.WrongFamily)
    }

    /**
     * Refuse an IPv4 source the host does not hold before the kernel is asked to send from it; see the
     * class KDoc for why IPv6 needs no such step.
     */
    fun verify(pin: SourcePin.Pinned) {
        if (pin !is SourcePin.Ipv4 || pin.bits in heldIpv4.value) return
        when (val locality = localityOf(pin)) {
            AddressLocality.Holds -> remember(pin.bits)
            AddressLocality.DoesNotHold -> throw refusal(pin.host, SourceAddressRejection.NotAssigned(EADDRNOTAVAIL))
            is AddressLocality.Unprobed -> throw refusal(pin.host, SourceAddressRejection.Unverified(locality.errno))
        }
    }

    /**
     * Classify a failed pinned send. The kernel has no errno meaning "that source is not mine", so
     * whether the source is to blame is asked of the host directly, once, on a send that already failed.
     */
    fun sendFailure(
        errnoCode: Int,
        pin: SourcePin.Pinned,
        attempted: Int,
        limit: Int,
    ): DatagramSendError =
        when (val locality = localityOf(pin)) {
            AddressLocality.Holds -> sendErrnoToError(errnoCode, attempted = attempted, limit = limit)
            AddressLocality.DoesNotHold -> {
                if (pin is SourcePin.Ipv4) forget(pin.bits)
                DatagramSendError.SourceAddressUnavailable(pin.host, SourceAddressRejection.NotAssigned(errnoCode))
            }
            is AddressLocality.Unprobed ->
                DatagramSendError.SourceAddressUnavailable(
                    pin.host,
                    SourceAddressRejection.Undetermined(sendErrno = errnoCode, probeErrno = locality.errno),
                )
        }

    private fun remember(bits: Long) {
        while (true) {
            val held = heldIpv4.value
            // A host holds a handful of addresses; a set this large means churn, so start over.
            val next = if (held.size >= MAX_HELD) setOf(bits) else held + bits
            if (heldIpv4.compareAndSet(held, next)) return
        }
    }

    private fun forget(bits: Long) {
        while (true) {
            val held = heldIpv4.value
            if (bits !in held) return
            if (heldIpv4.compareAndSet(held, held - bits)) return
        }
    }

    /**
     * Does this host hold [pin]'s address? Asked by binding a throwaway socket to it on port 0. Darwin's
     * `bind` accepts exactly the addresses assigned to an interface, and refuses the rest with
     * `EADDRNOTAVAIL`; every other refusal is a failure of the probe, not an answer.
     */
    private fun localityOf(pin: SourcePin.Pinned): AddressLocality =
        memScoped {
            val address =
                when (pin) {
                    is SourcePin.Ipv4 -> AppleSocketAddress(pin.host, 0, AddressFamily.IPv4, 0L, pin.bits)
                    is SourcePin.Ipv6 -> AppleSocketAddress(pin.host, 0, AddressFamily.IPv6, pin.hi, pin.lo)
                }
            val storage = alloc<sockaddr_storage>()
            val length = address.writeSockaddr(storage)
            val probe = socket(if (address.family == AddressFamily.IPv6) AF_INET6 else AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (probe < 0) return@memScoped AddressLocality.Unprobed(errno)
            try {
                if (bind(probe, storage.ptr.reinterpret(), length) == 0) {
                    AddressLocality.Holds
                } else {
                    val code = errno // before close(), which may overwrite it
                    if (code == EADDRNOTAVAIL) AddressLocality.DoesNotHold else AddressLocality.Unprobed(code)
                }
            } finally {
                close(probe)
            }
        }

    private fun refusal(
        host: String,
        reason: SourceAddressRejection,
    ) = DatagramSendException(DatagramSendError.SourceAddressUnavailable(host, reason))

    /** What the probe established. A verdict, so "could not tell" is never read as one of the answers. */
    private sealed interface AddressLocality {
        data object Holds : AddressLocality

        data object DoesNotHold : AddressLocality

        data class Unprobed(
            val errno: Int,
        ) : AddressLocality
    }

    /** An address as the kernel sends from it: IPv4 whether spelled natively or IPv4-mapped. */
    private sealed interface Ip {
        class V4(
            val bits: Long,
        ) : Ip

        class V6(
            val hi: Long,
            val lo: Long,
        ) : Ip
    }

    private fun AppleSocketAddress.ip(): Ip =
        when (family) {
            AddressFamily.IPv4 -> Ip.V4(lo and IPV4_MASK)
            AddressFamily.IPv6 -> if (hi == 0L && (lo ushr 32) == V4_MAPPED_PREFIX) Ip.V4(lo and IPV4_MASK) else Ip.V6(hi, lo)
        }

    private fun Ip.isUnspecified(): Boolean =
        when (this) {
            is Ip.V4 -> bits == 0L
            is Ip.V6 -> hi == 0L && lo == 0L
        }

    private companion object {
        const val IPV4_MASK = 0xFFFF_FFFFL
        const val V4_MAPPED_PREFIX = 0xFFFFL
        const val MAX_HELD = 64
    }
}

/** What one send does about its source. */
internal sealed interface SourcePin {
    /** No source named. The kernel routes and picks. */
    data object OsRouting : SourcePin

    /** Leave from a named address; [host] is how the caller spelled it, for the error it may produce. */
    sealed interface Pinned : SourcePin {
        val host: String

        /** Write the address into [bytes] (16 bytes of room) and return its `sa_family`. */
        fun writeSource(bytes: CPointer<UByteVar>): Int
    }

    class Ipv4(
        override val host: String,
        val bits: Long,
    ) : Pinned {
        override fun writeSource(bytes: CPointer<UByteVar>): Int {
            for (i in 0 until 4) bytes[i] = ((bits shr (24 - 8 * i)) and 0xFF).toUByte()
            return AF_INET
        }
    }

    class Ipv6(
        override val host: String,
        val hi: Long,
        val lo: Long,
    ) : Pinned {
        override fun writeSource(bytes: CPointer<UByteVar>): Int {
            for (i in 0 until 8) bytes[i] = ((hi shr (56 - 8 * i)) and 0xFF).toUByte()
            for (i in 0 until 8) bytes[8 + i] = ((lo shr (56 - 8 * i)) and 0xFF).toUByte()
            return AF_INET6
        }
    }
}

/** 16 bytes of scratch for [SourcePin.Pinned.writeSource], in the caller's arena. */
internal fun MemScope.sourceScratch(): CPointer<UByteVar> = allocArray(16)
