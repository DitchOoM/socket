package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.life.ConnEnd

/** Work any thread hands a loop. Runs on the loop, once, or is told [gone] once instead. */
internal abstract class LoopWork : InboxItem<LoopWork>() {
    abstract fun run(port: LoopPort)

    /** The loop had stopped with [end]; called instead of [run], on the thread that handed the work over or the loop's. */
    abstract fun gone(end: ConnEnd)

    final override fun self(): LoopWork = this
}
