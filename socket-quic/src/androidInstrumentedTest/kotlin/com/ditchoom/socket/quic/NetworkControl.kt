package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.netctrl.NetCtrlCommand
import com.ditchoom.socket.quic.netctrl.NetCtrlCommandCodec
import com.ditchoom.socket.quic.netctrl.NetCtrlFraming
import com.ditchoom.socket.quic.netctrl.NetCtrlResponse
import com.ditchoom.socket.quic.netctrl.NetCtrlResponseCodec
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Device-side client for the host [NetworkControlServer][com.ditchoom.socket.quic.netctrl.NetworkControlServer].
 *
 * Sends type-safe [NetCtrlCommand]s over TCP, receives [NetCtrlResponse]s.
 * All commands are synchronous — the call blocks until the host confirms execution.
 */
class NetworkControl(
    private val host: String = "10.0.2.2",
    private val port: Int = 9998,
) : AutoCloseable {
    private var socket: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null

    fun connect() {
        val s = Socket(host, port)
        s.soTimeout = 10_000
        socket = s
        inp = s.getInputStream()
        out = s.getOutputStream()
    }

    /** Check if the control server is reachable and responding. */
    fun isAvailable(): Boolean =
        try {
            if (socket == null) connect()
            sendCommand(NetCtrlCommand.Ping())
            true
        } catch (_: Exception) {
            false
        }

    fun blockUdp() {
        sendCommand(NetCtrlCommand.BlockUdp())
    }

    fun unblockUdp() {
        sendCommand(NetCtrlCommand.UnblockUdp())
    }

    fun addLatency(ms: Int) {
        sendCommand(NetCtrlCommand.AddLatency(ms))
    }

    fun removeLatency() {
        sendCommand(NetCtrlCommand.RemoveLatency())
    }

    /**
     * Activates airplane mode with pre-scheduled recovery.
     *
     * 1. Tells the host to schedule [AirplaneOff] after [recoveryDelayMs]
     * 2. Sends [AirplaneOn] — the TCP connection dies after this
     *
     * Call [waitForAirplaneModeRecovery] afterwards to wait for the scheduled recovery
     * and re-establish the control connection.
     */
    fun airplaneModeOn(recoveryDelayMs: Long = 5000) {
        // Schedule recovery BEFORE activating airplane mode
        val scheduleResponse = sendCommand(NetCtrlCommand.ScheduleAirplaneOff(recoveryDelayMs))
        require(scheduleResponse is NetCtrlResponse.Scheduled)

        // Activate airplane mode — TCP will die, so don't wait for response
        try {
            NetCtrlFraming.send(out!!, NetCtrlCommandCodec, NetCtrlCommand.AirplaneOn())
        } catch (_: IOException) {
            // Expected: the send might fail if the network dies fast
        }
        // Connection is now dead
        socket = null
        inp = null
        out = null
    }

    fun airplaneModeOff() {
        sendCommand(NetCtrlCommand.AirplaneOff())
    }

    /**
     * Waits for the scheduled airplane mode recovery, then reconnects the control channel.
     * @param waitMs Total time to wait (should be > the scheduled recovery delay + margin)
     */
    fun waitForAirplaneModeRecovery(waitMs: Long = 7000) {
        Thread.sleep(waitMs)
        reconnect()
    }

    fun cleanup() {
        sendCommand(NetCtrlCommand.Cleanup())
    }

    /** Reconnects the control channel (e.g., after airplane mode recovery). */
    fun reconnect() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        inp = null
        out = null
        connect()
        sendCommand(NetCtrlCommand.Ping()) // verify connection works
    }

    override fun close() {
        try {
            cleanup()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        inp = null
        out = null
    }

    private fun sendCommand(command: NetCtrlCommand): NetCtrlResponse {
        val o = out ?: throw IOException("Not connected")
        val i = inp ?: throw IOException("Not connected")
        NetCtrlFraming.send(o, NetCtrlCommandCodec, command)
        val response = NetCtrlFraming.recv(i, NetCtrlResponseCodec)
        if (response is NetCtrlResponse.Error) {
            throw RuntimeException("Network control error: ${response.message}")
        }
        return response
    }
}
