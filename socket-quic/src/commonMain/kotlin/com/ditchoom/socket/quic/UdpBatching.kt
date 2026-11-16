package com.ditchoom.socket.quic

/**
 * How many datagrams one system call of the run-to-completion core may carry. Each socket probes what its kernel
 * offers once, when it opens, and uses the newest of that this setting allows.
 */
enum class UdpBatching {
    /**
     * The newest the kernel offers: on Linux and Android, `UDP_SEGMENT` (GSO) groups, else `sendmmsg`/`recvmmsg`; on
     * macOS, `sendmsg_x`/`recvmsg_x`.
     */
    Newest,

    /**
     * The newest batching that uses only calls the platform SDK declares: as [Newest] on Linux and Android; one
     * datagram per call on macOS, whose batched calls are not in its public SDK. For builds an app store reviews.
     */
    PublicCallsOnly,

    /** Several datagrams per call, never `UDP_SEGMENT` groups. */
    WithoutSegments,

    /** One datagram per system call. */
    OneDatagramPerCall,
}
