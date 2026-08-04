package com.ditchoom.socket.udp

/**
 * **The host's own answer to "can a datagram of this size cross loopback here?", measured with a plain
 * socket and no part of this library involved.**
 *
 * [assertSendNeverSilentlyDrops] asserts that everything a channel advertises as writable actually
 * arrives. That assertion is only sound where the *host* can carry the datagram at all, and some hosts
 * cannot: WSL2 (measured on kernel 6.18.33.2-microsoft-standard-WSL2) reports `lo` with `mtu 65536` and
 * `net.core.{r,w}mem_default = 212992` — byte-for-byte the same as a healthy Linux — yet silently drops
 * every loopback datagram from **1473** bytes up, exactly MTU 1500 minus the IPv4 (20) and UDP (8)
 * headers. `sendto` returns the full length and the datagram never arrives: the WSL2 loopback path
 * declines to fragment. A vanilla Linux 6.x kernel on the same sysctls carries the full 65507 and
 * refuses 65508 with `EMSGSIZE`, which is the protocol ceiling and the right answer.
 *
 * So the host limit is invisible to configuration — no sysctl, MTU, or `uname` check distinguishes the
 * two — and it is indistinguishable *from the sending side* from the library bug this suite exists to
 * catch. Both look like "send returned normally, nothing arrived". Only an independent measurement can
 * tell them apart, which is what this is: probe the same size with a bare socket, and if the host cannot
 * carry it either, the leg is skipped and said so out loud. If the host **can** carry it, the library
 * must too — a regression still fails, which is the property that matters.
 *
 * ## Configured like the library, deliberately
 *
 * An implementation must widen `SO_SNDBUF` to [MAX_UDP_DATAGRAM_SIZE] the way every backend does
 * ([com.ditchoom.socket.udp.UdpSocket] on Apple, `sendBufferSize` on Node, the JDK internally). Probing
 * with a default socket would measure Darwin's 9216-byte `SO_SNDBUF` and report a host ceiling of 9216
 * on macOS — skipping the 65507 leg on the very platform whose silent drop motivated this suite. The
 * question is what the host can do for a socket set up like ours, not for an unconfigured one.
 */
internal fun interface HostLoopback {
    /** Whether a [size]-byte datagram sent over loopback with a plain socket actually arrives. */
    suspend fun carries(size: Int): Boolean
}
