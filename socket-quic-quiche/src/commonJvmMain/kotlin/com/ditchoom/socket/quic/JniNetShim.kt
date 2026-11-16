package com.ditchoom.socket.quic

/** [NetShim] over JNI (`src/jni/qnet_jni.c` in `libquiche_jni`): JDK 17–21 and Android. */
internal object JniNetShim : NetShim {
    init {
        NativeLibLoader.load("quiche_jni")
    }

    override val binding: NetBinding get() = NetBinding.Jni

    override fun pollerOpen(capacity: Int): Long = nPollerOpen(capacity)

    override fun pollerAdd(
        poller: Long,
        fd: Int,
        token: Int,
    ): Int = nPollerAdd(poller, fd, token)

    override fun pollerModify(
        poller: Long,
        fd: Int,
        token: Int,
        writable: Int,
    ): Int = nPollerModify(poller, fd, token, writable)

    override fun pollerRemove(
        poller: Long,
        fd: Int,
    ): Int = nPollerRemove(poller, fd)

    override fun pollerWait(
        poller: Long,
        mode: Int,
        nanos: Long,
    ): Int = nPollerWait(poller, mode, nanos)

    override fun pollerReadyAt(
        poller: Long,
        index: Int,
    ): Int = nPollerReadyAt(poller, index)

    override fun pollerWake(poller: Long): Int = nPollerWake(poller)

    override fun pollerClose(poller: Long) = nPollerClose(poller)

    override fun udpOpen(spec: Long): Int = nUdpOpen(spec)

    override fun udpCapability(
        fd: Int,
        spec: Long,
    ): Int = nUdpCapability(fd, spec)

    override fun udpSend(
        fd: Int,
        family: Int,
        mode: Int,
        base: Long,
        stride: Int,
        records: Long,
        count: Int,
        segments: Int,
    ): Long = nUdpSend(fd, family, mode, base, stride, records, count, segments)

    override fun udpRecv(
        fd: Int,
        mode: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long = nUdpRecv(fd, mode, records, count, size)

    override fun udpRecvCoalesced(
        fd: Int,
        records: Long,
        count: Int,
        size: Int,
        scratch: Long,
    ): Long = nUdpRecvCoalesced(fd, records, count, size, scratch)

    override fun udpError(fd: Int): Int = nUdpError(fd)

    override fun close(fd: Int): Int = nClose(fd)

    @JvmStatic private external fun nPollerOpen(capacity: Int): Long

    @JvmStatic private external fun nPollerAdd(
        poller: Long,
        fd: Int,
        token: Int,
    ): Int

    @JvmStatic private external fun nPollerModify(
        poller: Long,
        fd: Int,
        token: Int,
        writable: Int,
    ): Int

    @JvmStatic private external fun nPollerRemove(
        poller: Long,
        fd: Int,
    ): Int

    @JvmStatic private external fun nPollerWait(
        poller: Long,
        mode: Int,
        nanos: Long,
    ): Int

    @JvmStatic private external fun nPollerReadyAt(
        poller: Long,
        index: Int,
    ): Int

    @JvmStatic private external fun nPollerWake(poller: Long): Int

    @JvmStatic private external fun nPollerClose(poller: Long)

    @JvmStatic private external fun nUdpOpen(spec: Long): Int

    @JvmStatic private external fun nUdpCapability(
        fd: Int,
        spec: Long,
    ): Int

    @JvmStatic private external fun nUdpSend(
        fd: Int,
        family: Int,
        mode: Int,
        base: Long,
        stride: Int,
        records: Long,
        count: Int,
        segments: Int,
    ): Long

    @JvmStatic private external fun nUdpRecv(
        fd: Int,
        mode: Int,
        records: Long,
        count: Int,
        size: Int,
    ): Long

    @JvmStatic private external fun nUdpRecvCoalesced(
        fd: Int,
        records: Long,
        count: Int,
        size: Int,
        scratch: Long,
    ): Long

    @JvmStatic private external fun nUdpError(fd: Int): Int

    @JvmStatic private external fun nClose(fd: Int): Int
}
