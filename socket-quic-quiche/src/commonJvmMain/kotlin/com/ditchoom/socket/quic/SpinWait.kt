package com.ditchoom.socket.quic

/**
 * How a thread spins for a moment while another finishes a step: with the [Thread.onSpinWait] hint where the
 * runtime has it (the JVM, Android 13 and later), and with no hint where it does not. Chosen once, by looking the
 * method up.
 */
internal sealed interface SpinWait {
    fun pause()

    /** Hints the processor that this thread is spinning. */
    object Hinted : SpinWait {
        override fun pause() = Thread.onSpinWait()
    }

    /** Spins without a hint: the runtime has none to give. */
    object Unhinted : SpinWait {
        override fun pause() = Unit
    }

    companion object {
        val ofThisRuntime: SpinWait =
            try {
                Thread::class.java.getMethod("onSpinWait")
                Hinted
            } catch (_: NoSuchMethodException) {
                Unhinted
            }
    }
}
