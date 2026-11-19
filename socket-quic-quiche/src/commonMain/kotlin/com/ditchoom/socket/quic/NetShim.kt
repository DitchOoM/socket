package com.ditchoom.socket.quic

/**
 * The native stack's poller and UDP socket calls (`qnet`, `src/jni/qnet.h`), in the native library that carries
 * quiche. One implementation per binding: FFM, JNI, or Kotlin/Native cinterop. Integers only, as the C functions
 * take them; a negative result is a negated [SocketRefusal] code. The `checked` calls of `NetShimCalls.kt` turn the
 * refusals a caller cannot act on into a [NativeSocketRefusal].
 */
internal interface NetShim {
    val binding: NetBinding

    fun pollerOpen(capacity: Int): Long

    fun pollerAdd(
        poller: Long,
        fd: Int,
        token: Int,
    ): Int

    fun pollerModify(
        poller: Long,
        fd: Int,
        token: Int,
        writable: Int,
    ): Int

    fun pollerRemove(
        poller: Long,
        fd: Int,
    ): Int

    /** `count shl 1 or woken`, or a negated code. */
    fun pollerWait(
        poller: Long,
        mode: Int,
        nanos: Long,
    ): Int

    /** `token shl 2 or bits` of the [index]th ready socket of the last wait. */
    fun pollerReadyAt(
        poller: Long,
        index: Int,
    ): Int

    fun pollerWake(poller: Long): Int

    fun pollerClose(poller: Long)

    /** `qnet`'s fingerprint of the spec block's layout, to compare with [com.ditchoom.socket.quic.core.udp.SpecLayout.FINGERPRINT]. */
    fun specLayout(): Int

    /** Opens the socket the spec block at [spec] describes; the descriptor, or a negated code. */
    fun udpOpenSocket(spec: Long): Int

    /** The batching the socket's kernel offers: `QN_CAP_*` bits, and the GSO segment ceiling shifted 8 bits up. */
    fun udpCapabilities(fd: Int): Int

    /** Turns `UDP_GRO` on: 1 on, 0 this platform has none, a negated code when refused. */
    fun udpSetGro(fd: Int): Int

    /** Sets DF on the socket's datagrams: 0 when set, else a negated code. [family] is the socket's, 4 or 6. */
    fun udpSetDontFragment(
        fd: Int,
        family: Int,
    ): Int

    /** Datagrams taken in the low 32 bits, the refusal that stopped the batch in bits 32..47, flags above. */
    fun udpSend(
        fd: Int,
        family: Int,
        mode: Int,
        base: Long,
        stride: Int,
        records: Long,
        count: Int,
        segments: Int,
    ): Long

    /** Datagrams received in the low 32 bits, the refusal that stopped the receive in bits 32..47. */
    fun udpRecv(
        fd: Int,
        mode: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long

    /**
     * [udpRecv] for a socket that coalesces (`UDP_GRO` on): one `recvmmsg`, each message split by its own segment size
     * into [count] slots exactly as separate datagrams. The socket's scratch is `qnet`'s own, made when `UDP_GRO` was turned on.
     */
    fun udpRecvCoalesced(
        fd: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long

    /** The socket's pending error (0 for none), or a negated code when the descriptor is no longer a socket. */
    fun udpError(fd: Int): Int

    fun close(fd: Int): Int
}

/** How this process reaches `qnet`. */
internal enum class NetBinding { Ffm, Jni, Cinterop }

/** What a `qnet` call refused, by the code `qnet` maps every platform's errno to. */
internal enum class SocketRefusal(
    val code: Int,
) {
    EAgain(1),
    EMsgSize(2),
    ENetUnreach(3),
    EHostUnreach(4),
    EConnRefused(5),
    ENoBufs(6),
    EAcces(7),
    EBadF(8),
    EIo(9),
    EAddrInUse(10),
    EAddrNotAvail(11),
    EAfNoSupport(12),
    ENoMem(13),
    EInval(14),
    ENoProtoOpt(15),
    ENoSys(16),
    EPerm(17),
    EPipe(18),
    Unmapped(99),
    ;

    companion object {
        fun of(code: Int): SocketRefusal {
            val all = entries
            for (i in all.indices) if (all[i].code == code) return all[i]
            return Unmapped
        }
    }
}

/** A `qnet` refusal as a throwable: what a socket call that cannot be retried, or a failed open, reports. */
internal class NativeSocketRefusal(
    val refusal: SocketRefusal,
) : Exception() {
    override val message: String get() = refusal.name
}
