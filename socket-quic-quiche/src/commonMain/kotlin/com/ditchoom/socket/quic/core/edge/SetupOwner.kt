package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import kotlinx.coroutines.Runnable

/**
 * Who releases what a server's setup opened, at each step of it. A setup that fails releases through its owner at that
 * step, so the memory, the poller, the quiche config and the socket each have exactly one releaser, whatever step
 * failed. Until the loop is built the setup frees the memory, itself and then through the memory hold; from then on
 * only the loop's stop does.
 */
internal sealed interface SetupOwner {
    /** The setup frees the memory. */
    class Allocating(
        val memory: EdgeMemory,
    ) : SetupOwner

    /** The setup also closes the poller. */
    class Polled(
        val memory: EdgeMemory,
        val poller: Poller,
    ) : SetupOwner

    /** The setup also frees the quiche config. */
    class Configured(
        val memory: EdgeMemory,
        val poller: Poller,
        val config: QuicheConfig,
    ) : SetupOwner

    /** The setup also closes the socket it opened. */
    class Opened(
        val memory: EdgeMemory,
        val poller: Poller,
        val config: QuicheConfig,
        val socket: DatagramBatchIo,
    ) : SetupOwner

    /** The setup also frees the server's own address, encoded for quiche. */
    class Addressed(
        val memory: EdgeMemory,
        val poller: Poller,
        val config: QuicheConfig,
        val socket: DatagramBatchIo,
        val local: EncodedSockAddr,
    ) : SetupOwner

    /**
     * The memory hold owns the memory, the config, the address and the token keys: the setup releases the hold's founding
     * claim, and still closes the socket and the poller.
     */
    class Held(
        val poller: Poller,
        val socket: DatagramBatchIo,
        val hold: MemoryHold,
    ) : SetupOwner

    /** The loop owns the poller, and its memory hold the memory and the config; the setup still closes the socket. */
    class Built(
        val loop: Loop,
        val socket: DatagramBatchIo,
    ) : SetupOwner

    /** The loop owns the socket too, through its endpoint: its stop, on this thread, releases everything once. */
    class Placed(
        val loop: Loop,
    ) : SetupOwner

    /** The loop runs on a thread of its own: only its stop releases, once it is asked to. */
    class Started(
        val loop: Loop,
    ) : SetupOwner

    /** The server is built: its stop releases everything. */
    data object Served : SetupOwner
}

/** Releases what a serve that failed at this step opened, each thing once; [api] frees the quiche config. */
internal fun SetupOwner.endFailed(api: QuicheApi) {
    when (this) {
        is SetupOwner.Allocating -> memory.freeAll()
        is SetupOwner.Polled -> {
            poller.close()
            memory.freeAll()
        }
        is SetupOwner.Configured -> {
            poller.close()
            api.configFree(config)
            memory.freeAll()
        }
        is SetupOwner.Opened -> {
            socket.close()
            poller.close()
            api.configFree(config)
            memory.freeAll()
        }
        is SetupOwner.Addressed -> {
            socket.close()
            poller.close()
            api.configFree(config)
            local.free()
            memory.freeAll()
        }
        is SetupOwner.Held -> {
            socket.close()
            poller.close()
            hold.founding.release()
        }
        is SetupOwner.Built -> {
            loop.stopHere()
            socket.close()
        }
        is SetupOwner.Placed -> loop.stopHere()
        is SetupOwner.Started -> loop.close(Runnable { })
        is SetupOwner.Served -> Unit
    }
}
