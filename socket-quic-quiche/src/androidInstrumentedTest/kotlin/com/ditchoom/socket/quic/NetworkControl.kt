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
 *
 * [endpoint] is carried, never assumed: the server binds an OS-assigned port and is reached over an
 * `adb reverse tcp:` mapping, which puts it on the device's own loopback on an emulator and on real
 * hardware alike. (`adb reverse` is TCP-only, which is exactly why the *UDP* quic-echo harness in
 * [HarnessEndpoints] cannot share this address.)
 */
internal class NetworkControl(
    private val endpoint: HarnessEndpoint,
) : AutoCloseable {
    private var socket: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null

    fun connect() {
        val s = Socket(endpoint.host, endpoint.port)
        s.soTimeout = 10_000
        socket = s
        inp = s.getInputStream()
        out = s.getOutputStream()
    }

    /**
     * Ping the control server. Returns `null` when it answered, otherwise **the failure that stopped
     * it** — a `Boolean` here threw away the one piece of information a skip message needs, which is
     * why the caller's skip could only ever say "not available".
     */
    fun probe(): Throwable? =
        try {
            if (socket == null) connect()
            sendCommand(NetCtrlCommand.Ping())
            null
        } catch (e: Exception) {
            e
        }

    /**
     * Ask the host whether it can actually impair this device's network (#389).
     *
     * Distinct from [probe], which answers "the control server is up". Every impairment runs as
     * `adb shell su 0 …`, and on a device without root each of those fails; the server used to log
     * that as non-fatal and answer `Ok`, so five migration tests passed against a completely healthy
     * network. Returns the host's own answer — [NetCtrlResponse.ImpairmentAvailable] or
     * [NetCtrlResponse.ImpairmentUnavailable] carrying the reason — so a caller's skip can name the
     * missing capability instead of saying "not available".
     */
    fun queryImpairment(): NetCtrlResponse = sendCommand(NetCtrlCommand.QueryImpairment())

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
        // Close it, do not just forget it. "The connection is now dead" is a prediction about the
        // radio, and it is only true where airplane mode actually fires; where it does not, nulling
        // the field leaks a socket the server still considers live — and NetworkControlServer serves
        // ONE client at a time, so the leak parks its accept loop and every later test's Ping times
        // out against a server that is up. Closing makes the server see EOF either way, which is the
        // state the prediction was assuming.
        closeQuietly()
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
        closeQuietly()
        connect()
        sendCommand(NetCtrlCommand.Ping()) // verify connection works
    }

    override fun close() {
        try {
            cleanup()
        } catch (_: IOException) {
        }
        closeQuietly()
    }

    /**
     * Drop the transport and forget it, in that order.
     *
     * One place, because the order is the whole content: nulling the fields without closing the
     * socket leaves it open with no handle left to close it by — see [airplaneModeOn], where that
     * shape parked the host server's single-client accept loop.
     */
    private fun closeQuietly() {
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
