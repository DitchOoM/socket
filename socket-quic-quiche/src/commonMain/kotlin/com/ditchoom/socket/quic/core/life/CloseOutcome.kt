package com.ditchoom.socket.quic.core.life

/** How a close asked of a connection went. */
internal sealed interface CloseOutcome {
    /** Everything written before the close point was delivered as the graceful close waited for; then it closed. */
    data object Drained : CloseOutcome

    /** The graceful close's limit passed first: it closed then, and what was still undelivered is abandoned. */
    data object LimitReached : CloseOutcome

    /** It closed at once: an immediate close, or one that cut a graceful one short. */
    data object Immediate : CloseOutcome

    /** The connection ended, or was ending, another way before its close went: [end] says how. */
    class EndedFirst(
        val end: ConnEnd,
    ) : CloseOutcome

    /** No close was asked before the connection ended. */
    data object NotAsked : CloseOutcome
}

/** Whether this call recorded the end, with how a close asked of the connection went, or another had already. */
internal sealed interface Ending {
    class First(
        val outcome: CloseOutcome,
    ) : Ending

    data object Already : Ending
}
