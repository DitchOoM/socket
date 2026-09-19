package com.ditchoom.socket.quic

/**
 * How far a client connect attempt got, and therefore what its `finally` still owes.
 *
 * Establishing a QUIC connection acquires resources in stages, and a throw can land in any of them.
 * The resources acquired so far decide what teardown owes: a connected UDP socket exists long before
 * the handshake can be known to succeed, and once establishment completes the connection owns its own
 * teardown and the call site must release nothing — releasing anyway would close a live connection's
 * socket.
 *
 * A single `established` flag read by an `if` names only the last stage: it is correct about the
 * config and the scope and has nothing to say about the channel, and an `if` cannot be incomplete out
 * loud. As a sealed family consumed by an exhaustive `when`, each stage has to state its own answer,
 * and a stage added later cannot inherit whichever branch happened to be the default.
 *
 * Ordering is the progression itself: [BeforeChannel] → [ChannelOpen] → [ConnectionOwnsTeardown]. The
 * three client actuals (`commonJvmMain`, `appleMain`, `linuxMain`) each track their own value —
 * they free the quiche config at different points — but the stages are the same, which is why the
 * shape is shared rather than reimplemented per platform.
 */
internal sealed interface ConnectProgress {
    /**
     * No UDP channel exists yet. A failure here releases only what the call site created before
     * opening one.
     */
    data object BeforeChannel : ConnectProgress

    /**
     * A connected UDP channel is open and nothing else owns it yet — teardown must close it.
     *
     * Holds the [UdpChannel] wrapper rather than the raw datagram channel on purpose: closing the raw
     * channel leaves the selector/reader coroutine parked, so the wrapper is the only handle that
     * releases both.
     */
    data class ChannelOpen(
        val channel: UdpChannel,
    ) : ConnectProgress

    /**
     * The peer and local sockaddrs are encoded into pinned native memory and nothing owns them yet —
     * teardown must free both, then release everything [ChannelOpen] owes.
     *
     * The encodings are made before `quiche_connect`, and from there until the driver starts every
     * exit — `quiche_connect` refusing, `recv_info`/`send_info` allocation failing, the driver's
     * constructor throwing — lands here. Running [ChannelOpen]'s arm instead would leak two pinned
     * buffers per failed attempt, forever, on every backend. Naming the stage is what makes the free
     * impossible to forget, in the same way [ChannelOpen] makes the socket's.
     */
    data class SockAddrsPinned(
        val channel: UdpChannel,
        val peer: EncodedSockAddr,
        val local: EncodedSockAddr,
    ) : ConnectProgress

    /**
     * The driver is running and owns the sockaddr encodings through its `onCleanup`, which the driver
     * loop's `finally` runs on any exit including cancellation — so teardown here must NOT free them
     * (a second free of memory the driver is about to free) and owes only what [ChannelOpen] does.
     *
     * This is the stage a caller's establishment deadline fails in, and it leaks nothing: cancelling
     * the scope reaches the driver's cleanup. The one exit it does not cover is a scope cancelled
     * before the driver coroutine was ever dispatched, when `run()` and its `finally` never execute
     * at all; that also strands the quiche connection.
     */
    data class DriverStarted(
        val channel: UdpChannel,
    ) : ConnectProgress

    /**
     * Establishment completed and the connection owns its full teardown through its own `onRelease`.
     * The call site must release nothing — anything it closed here would be closed underneath a live
     * connection.
     */
    data object ConnectionOwnsTeardown : ConnectProgress
}
