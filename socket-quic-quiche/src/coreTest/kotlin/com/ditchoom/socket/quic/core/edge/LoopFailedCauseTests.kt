package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.test.Test
import kotlin.test.assertSame

/** A connection whose loop thread threw reports what it threw, as the cause of the exception its callers get. */
class LoopFailedCauseTests {
    private class Boom : Throwable()

    @Test
    fun theThrowableOfAFailedLoopIsTheCauseOfTheCloseExceptionCallersGet() {
        val boom = Boom()
        val raised = QuicCloseException(publicEndOf(ConnEnd.LoopFailed(Thrown(boom))), QuicCloseCircumstance.LoopThreadFailed)
        assertSame(boom, raised.cause)
    }
}
