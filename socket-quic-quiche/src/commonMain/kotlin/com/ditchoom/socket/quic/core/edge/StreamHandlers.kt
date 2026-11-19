package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.core.sched.HANDLER_FAILED
import com.ditchoom.socket.quic.core.sched.HandlerFailure
import com.ditchoom.socket.quic.core.sched.HandlerObservation
import com.ditchoom.socket.quic.core.sched.HandlerPlacement
import com.ditchoom.socket.quic.core.sched.HandlerSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Runs [handler] for every stream the peer opens, placed by [placement], each in the connection's supervisor scope: a
 * handler that throws resets its own stream with [HANDLER_FAILED] and is reported; every handler is cancelled when the
 * connection closes. SPIKE.
 */
internal fun CoreConnection.handleStreams(
    placement: HandlerPlacement,
    handler: suspend CoroutineScope.(QuicByteStream) -> Unit,
): Job {
    val handlers = loopHandlers
    val name = identity.session.hex
    val job =
        launch(dispatcherOf(placement) + HandlerSite.Accepting(name)) {
            try {
                supervisorScope {
                    streams().collect { stream ->
                        val site = HandlerSite.Stream(name, stream.streamId)
                        launch(site) {
                            try {
                                handler(stream)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (closed: QuicCloseException) {
                                Unit
                            } catch (failure: Exception) {
                                handlers.emit(HandlerObservation.Failed(site, HandlerFailure.Threw(failure)))
                                withContext(NonCancellable) { stream.reset(HANDLER_FAILED) }
                            }
                        }
                    }
                }
            } catch (closed: QuicCloseException) {
                Unit
            }
        }
    closedSignal.invokeOnCompletion { job.cancel() }
    return job
}

/** The dispatcher [placement] names; placing one on the loop turns its handler phase on. */
private fun CoreConnection.dispatcherOf(placement: HandlerPlacement): CoroutineDispatcher =
    when (placement) {
        is HandlerPlacement.OnLoop -> loopHandlers.also { it.activate() }
        is HandlerPlacement.OffLoop -> placement.dispatcher
    }
