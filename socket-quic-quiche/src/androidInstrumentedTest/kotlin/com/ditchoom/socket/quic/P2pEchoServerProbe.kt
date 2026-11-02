package com.ditchoom.socket.quic

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The phone as the server: a QUIC echo server on every address this device has, for another phone on
 * the same network to walk against directly, with no server in between. It serves the walk server's
 * echo ([echoConnection]) for `p2pMinutes`, then returns. Launched by `device-probe/p2p-server.sh`,
 * which reads the `P2P-READY` line for the port and addresses to hand the client.
 */
@RunWith(AndroidJUnit4::class)
class P2pEchoServerProbe {
    private fun arg(
        name: String,
        fallback: String,
    ): String = InstrumentationRegistry.getArguments().getString(name) ?: fallback

    @Test
    fun serve() {
        val port = arg("p2pPort", "44433").toInt()
        val minutes = arg("p2pMinutes", "30").toLong()
        val options =
            QuicOptions(
                alpnProtocols = listOf("test"),
                verifyPeer = false,
                idleTimeout = 30.seconds,
                datagrams = DatagramOptions(),
            )
        runBlocking(Dispatchers.IO) {
            withQuicServer(port = port, tlsConfig = AndroidTestCerts.tlsConfig, quicOptions = options) {
                say("P2P-READY port=${this.port} addresses=${addresses().joinToString(",")}")
                withTimeoutOrNull(minutes.minutes) { connections { echoConnection(options) } }
                say("P2P-DONE minutes=$minutes")
            }
        }
    }

    /** The addresses another device could reach this one at: everything but loopback and link-local. */
    private fun addresses(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nic ->
            nic.inetAddresses
                .toList()
                .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                .map { "${nic.name}/${it.hostAddress}" }
        }

    private fun say(line: String) {
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$line\n") })
    }

    private companion object {
        const val TAG = "P2pEchoServer"
    }
}
