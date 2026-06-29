package com.ditchoom.socket

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class LoopbackEchoInstrumentedTest {
    @Test
    fun loopbackEchoRoundTrip() =
        runBlocking {
            withTimeout(10.seconds) {
                val server = ServerSocket.allocate()
                val acceptedClientFlow = server.bind()
                val serverDone = Mutex(locked = true)
                val serverJob =
                    launch(Dispatchers.Default) {
                        acceptedClientFlow.collect { serverToClient ->
                            val received = serverToClient.readString(deadline = 5.seconds)
                            serverToClient.writeString(received, deadline = 5.seconds)
                            serverToClient.close()
                            serverDone.unlock()
                            return@collect
                        }
                    }

                val port = server.port()
                assertTrue("server port should be > 0", port > 0)

                val text = "loopback-echo-android-instrumented"
                val client = ClientSocket.allocate()
                client.open(port, hostname = "127.0.0.1", config = TransportConfig(connectTimeout = 5.seconds))
                client.writeString(text, deadline = 5.seconds)
                val echoed = client.readString(deadline = 5.seconds)
                assertEquals(text, echoed)

                client.close()
                serverDone.lock()
                server.close()
                serverJob.cancel()
            }
        }
}
