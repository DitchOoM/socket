package com.ditchoom.socket.quic

import kotlin.concurrent.AtomicReference

/** Kotlin/Native has no public compare-and-set on a field, so the cell is a second object per deadline here. */
internal actual abstract class StateCell {
    private lateinit var cell: AtomicReference<NodeState>

    protected actual fun setInitial(state: NodeState) {
        cell = AtomicReference(state)
    }

    protected actual fun loadState(): NodeState = cell.value

    protected actual fun swapState(
        expected: NodeState,
        new: NodeState,
    ): Boolean = cell.compareAndSet(expected, new)
}
