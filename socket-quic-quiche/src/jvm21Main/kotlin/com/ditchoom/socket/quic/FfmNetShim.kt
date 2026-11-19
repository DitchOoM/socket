@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.socket.quic

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * [NetShim] over FFM downcalls to the `qn_*` functions of the JNI shim library (which carries `qnet`). Integers and
 * addresses cross as `int`/`long`, so no call builds a `MemorySegment`.
 */
internal class FfmNetShim private constructor(
    /** Witness that [NetCalls] is open, so a packaging failure surfaces at load and not at the first call. */
    private val calls: NetCalls,
) : NetShim {
    override val binding: NetBinding get() = NetBinding.Ffm

    override fun pollerOpen(capacity: Int): Long = NetCalls.hPollerOpen.invokeExact(capacity) as Long

    override fun pollerAdd(
        poller: Long,
        fd: Int,
        token: Int,
    ): Int = NetCalls.hPollerAdd.invokeExact(poller, fd, token) as Int

    override fun pollerModify(
        poller: Long,
        fd: Int,
        token: Int,
        writable: Int,
    ): Int = NetCalls.hPollerModify.invokeExact(poller, fd, token, writable) as Int

    override fun pollerRemove(
        poller: Long,
        fd: Int,
    ): Int = NetCalls.hPollerRemove.invokeExact(poller, fd) as Int

    override fun pollerWait(
        poller: Long,
        mode: Int,
        nanos: Long,
    ): Int = NetCalls.hPollerWait.invokeExact(poller, mode, nanos) as Int

    override fun pollerReadyAt(
        poller: Long,
        index: Int,
    ): Int = NetCalls.hPollerReadyAt.invokeExact(poller, index) as Int

    override fun pollerWake(poller: Long): Int = NetCalls.hPollerWake.invokeExact(poller) as Int

    override fun pollerClose(poller: Long) {
        NetCalls.hPollerClose.invokeExact(poller)
    }

    override fun specLayout(): Int = NetCalls.hSpecLayout.invokeExact() as Int

    override fun udpOpenSocket(spec: Long): Int = NetCalls.hUdpOpenSocket.invokeExact(spec) as Int

    override fun udpCapabilities(fd: Int): Int = NetCalls.hUdpCapabilities.invokeExact(fd) as Int

    override fun udpSetGro(fd: Int): Int = NetCalls.hUdpSetGro.invokeExact(fd) as Int

    override fun udpSetDontFragment(
        fd: Int,
        family: Int,
    ): Int = NetCalls.hUdpSetDontFragment.invokeExact(fd, family) as Int

    override fun udpSend(
        fd: Int,
        family: Int,
        mode: Int,
        base: Long,
        stride: Int,
        records: Long,
        count: Int,
        segments: Int,
    ): Long = NetCalls.hUdpSend.invokeExact(fd, family, mode, base, stride, records, count, segments) as Long

    override fun udpRecv(
        fd: Int,
        mode: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long = NetCalls.hUdpRecv.invokeExact(fd, mode, records, count, size) as Long

    override fun udpRecvCoalesced(
        fd: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long = NetCalls.hUdpRecvCoalesced.invokeExact(fd, records, count, size) as Long

    override fun udpError(fd: Int): Int = NetCalls.hUdpError.invokeExact(fd) as Int

    override fun close(fd: Int): Int = NetCalls.hClose.invokeExact(fd) as Int

    companion object {
        fun create(): FfmNetShim = FfmNetShim(NetCalls)
    }
}

/**
 * The `qn_*` downcalls, `static final` so C2 compiles each as a direct native call, not a generic handle
 * invocation. Bound to the JNI shim [extractedJniShim] extracts, for the process lifetime.
 */
internal object NetCalls {
    private val linker: Linker = Linker.nativeLinker()

    // Process lifetime, never an unloadable arena: the shim links libquiche, which may not be unloaded.
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(extractedJniShim(), Arena.global())

    private fun downcall(
        name: String,
        desc: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(lookup.find(name).orElseThrow { UnsatisfiedLinkError("qnet: $name") }, desc)

    @JvmField
    val hPollerOpen: MethodHandle = downcall("qn_poller_open", FunctionDescriptor.of(JAVA_LONG, JAVA_INT))

    @JvmField
    val hPollerAdd: MethodHandle = downcall("qn_poller_add", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT))

    @JvmField
    val hPollerModify: MethodHandle =
        downcall("qn_poller_modify", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT))

    @JvmField
    val hPollerRemove: MethodHandle = downcall("qn_poller_remove", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT))

    @JvmField
    val hPollerWait: MethodHandle = downcall("qn_poller_wait", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG))

    @JvmField
    val hPollerReadyAt: MethodHandle = downcall("qn_poller_ready_at", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT))

    @JvmField
    val hPollerWake: MethodHandle = downcall("qn_poller_wake", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))

    @JvmField
    val hPollerClose: MethodHandle = downcall("qn_poller_close", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hSpecLayout: MethodHandle = downcall("qn_spec_layout", FunctionDescriptor.of(JAVA_INT))

    @JvmField
    val hUdpOpenSocket: MethodHandle = downcall("qn_udp_open_socket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))

    @JvmField
    val hUdpCapabilities: MethodHandle = downcall("qn_udp_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    @JvmField
    val hUdpSetGro: MethodHandle = downcall("qn_udp_set_gro", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    @JvmField
    val hUdpSetDontFragment: MethodHandle =
        downcall("qn_udp_set_dont_fragment", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))

    @JvmField
    val hUdpSend: MethodHandle =
        downcall(
            "qn_udp_send",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT),
        )

    @JvmField
    val hUdpRecv: MethodHandle =
        downcall("qn_udp_recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT))

    @JvmField
    val hUdpRecvCoalesced: MethodHandle =
        downcall("qn_udp_recv_coalesced", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT))

    @JvmField
    val hUdpError: MethodHandle = downcall("qn_udp_error", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    @JvmField
    val hClose: MethodHandle = downcall("qn_close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
}
