package com.ditchoom.socket.quic.core.udp

/** The granted buffers of a socket whose platform reports them, as every JVM socket's does. */
internal val SocketReport.buffers: GrantedBuffers
    get() =
        when (val reported = granted) {
            is Granted.Reported -> reported.buffers
            is Granted.NotReported -> throw AssertionError("a JVM socket reports its buffers")
        }
