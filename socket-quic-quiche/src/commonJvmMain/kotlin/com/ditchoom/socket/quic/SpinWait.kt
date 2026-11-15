package com.ditchoom.socket.quic

/**
 * How a thread waits a moment for another to finish a step: [Thread.onSpinWait] where the runtime has it (the JVM,
 * Android 13 and later), [Thread.yield] where it does not. Chosen once, by looking the method up.
 */
internal sealed interface SpinWait {
    fun pause()

    /** Hints the processor that this thread is spinning. */
    object OnSpinWait : SpinWait {
        override fun pause() = Thread.onSpinWait()
    }

    /** Gives the processor to another thread; on every runtime. */
    object Yield : SpinWait {
        override fun pause() = Thread.yield()
    }

    companion object {
        val ofThisRuntime: SpinWait =
            try {
                Thread::class.java.getMethod("onSpinWait")
                OnSpinWait
            } catch (_: NoSuchMethodException) {
                Yield
            }
    }
}
