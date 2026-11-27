package com.ditchoom.socket.quic.core.observe

/** State touched only by the thread that holds the connection's owner word. */
internal annotation class Confined

/** State touched only by its loop thread. */
internal annotation class LoopOnly

/** State written by one owner and read by any thread: one atomic holding an immutable value. */
internal annotation class Published

/** A lock-free multi-producer hand-off. */
internal annotation class Queue

/** A lock-free free list. */
internal annotation class Pool

/** State used by one caller coroutine at a time. */
internal annotation class CallerSide

/** An intrusive link written only by the queue that owns the item. */
internal annotation class Link

/**
 * A sealed state with one holder: only the file [owner] names (a path under core, such as `loop/Seat.kt`) may declare
 * a mutable holder of it, a `var` or an atomic cell. Anywhere else it is passed as a value, published, or named by a
 * ticket.
 */
@Target(AnnotationTarget.CLASS)
internal annotation class SingleOwner(
    val owner: String,
)
