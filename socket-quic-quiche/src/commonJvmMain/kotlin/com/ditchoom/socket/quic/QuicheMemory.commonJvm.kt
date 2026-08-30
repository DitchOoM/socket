package com.ditchoom.socket.quic

/**
 * An empty `synchronized` block, which is the reachability fence available on **both** JVM legs of
 * this source set.
 *
 * `java.lang.ref.Reference.reachabilityFence` is the purpose-built primitive and is free on HotSpot,
 * but it only reached Android in API 28 and this module's `minSdk` is 24 — so on three shipped
 * Android versions the call would resolve to nothing and fail at the moment it mattered. The empty
 * monitor is the idiom `Reference.reachabilityFence`'s own javadoc names for exactly that gap: the
 * `monitorenter`/`monitorexit` pair keeps [owner] live to this point, and HotSpot's lock elimination
 * cannot remove it because [owner] escaped its allocation site long before it got here (it arrived
 * through a [QuicheCmd] field).
 *
 * The cost is one uncontended monitor per quiche call that touches a buffer — nothing measurable
 * beside the FFI transition and the command allocation it accompanies. Nobody else ever locks a
 * buffer, so it can never contend.
 */
internal actual fun reachabilityFence(owner: Any) {
    synchronized(owner) {}
}
