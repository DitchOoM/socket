package com.ditchoom.socket.quic

import java.util.concurrent.atomic.AtomicReference

internal actual abstract class StateCell : AtomicReference<NodeState>() {
    protected actual fun setInitial(state: NodeState) {
        set(state)
    }

    protected actual fun loadState(): NodeState = get()

    protected actual fun swapState(
        expected: NodeState,
        new: NodeState,
    ): Boolean = compareAndSet(expected, new)
}
