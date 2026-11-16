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
    private val lookup: SymbolLookup,
) : NetShim {
    private val linker = Linker.nativeLinker()

    private fun downcall(
        name: String,
        desc: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(lookup.find(name).orElseThrow { UnsatisfiedLinkError("qnet: $name") }, desc)

    private val hPollerOpen = downcall("qn_poller_open", FunctionDescriptor.of(JAVA_LONG, JAVA_INT))
    private val hPollerAdd = downcall("qn_poller_add", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT))
    private val hPollerModify =
        downcall("qn_poller_modify", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT))
    private val hPollerRemove = downcall("qn_poller_remove", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT))
    private val hPollerWait = downcall("qn_poller_wait", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG))
    private val hPollerReadyAt = downcall("qn_poller_ready_at", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT))
    private val hPollerWake = downcall("qn_poller_wake", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    private val hPollerClose = downcall("qn_poller_close", FunctionDescriptor.ofVoid(JAVA_LONG))
    private val hUdpOpen = downcall("qn_udp_open", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    private val hUdpCapability = downcall("qn_udp_capability", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG))
    private val hUdpSend =
        downcall(
            "qn_udp_send",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT),
        )
    private val hUdpRecv =
        downcall("qn_udp_recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT))
    private val hUdpRecvCoalesced =
        downcall("qn_udp_recv_coalesced", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG))
    private val hUdpError = downcall("qn_udp_error", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    private val hClose = downcall("qn_close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    override val binding: NetBinding get() = NetBinding.Ffm

    override fun pollerOpen(capacity: Int): Long = hPollerOpen.invokeExact(capacity) as Long

    override fun pollerAdd(
        poller: Long,
        fd: Int,
        token: Int,
    ): Int = hPollerAdd.invokeExact(poller, fd, token) as Int

    override fun pollerModify(
        poller: Long,
        fd: Int,
        token: Int,
        writable: Int,
    ): Int = hPollerModify.invokeExact(poller, fd, token, writable) as Int

    override fun pollerRemove(
        poller: Long,
        fd: Int,
    ): Int = hPollerRemove.invokeExact(poller, fd) as Int

    override fun pollerWait(
        poller: Long,
        mode: Int,
        nanos: Long,
    ): Int = hPollerWait.invokeExact(poller, mode, nanos) as Int

    override fun pollerReadyAt(
        poller: Long,
        index: Int,
    ): Int = hPollerReadyAt.invokeExact(poller, index) as Int

    override fun pollerWake(poller: Long): Int = hPollerWake.invokeExact(poller) as Int

    override fun pollerClose(poller: Long) {
        hPollerClose.invokeExact(poller)
    }

    override fun udpOpen(spec: Long): Int = hUdpOpen.invokeExact(spec) as Int

    override fun udpCapability(
        fd: Int,
        spec: Long,
    ): Int = hUdpCapability.invokeExact(fd, spec) as Int

    override fun udpSend(
        fd: Int,
        family: Int,
        mode: Int,
        base: Long,
        stride: Int,
        records: Long,
        count: Int,
        segments: Int,
    ): Long = hUdpSend.invokeExact(fd, family, mode, base, stride, records, count, segments) as Long

    override fun udpRecv(
        fd: Int,
        mode: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long = hUdpRecv.invokeExact(fd, mode, records, count, size) as Long

    override fun udpRecvCoalesced(
        fd: Int,
        records: Long,
        count: Int,
        size: Int,
        scratch: Long,
    ): Long = hUdpRecvCoalesced.invokeExact(fd, records, count, size, scratch) as Long

    override fun udpError(fd: Int): Int = hUdpError.invokeExact(fd) as Int

    override fun close(fd: Int): Int = hClose.invokeExact(fd) as Int

    companion object {
        /** Process lifetime, never an unloadable arena: the shim links libquiche, which may not be unloaded. */
        fun create(libraryPath: String): FfmNetShim = FfmNetShim(SymbolLookup.libraryLookup(libraryPath, Arena.global()))
    }
}
