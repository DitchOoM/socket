package com.ditchoom.socket

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
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
                            val buffer = serverToClient.read(5.seconds)
                            val received = buffer.readString(buffer.remaining(), Charset.UTF8)
                            serverToClient.writeString(received, Charset.UTF8, 5.seconds)
                            serverToClient.close()
                            serverDone.unlock()
                            return@collect
                        }
                    }

                val port = server.port()
                assertTrue("server port should be > 0", port > 0)

                val text = "loopback-echo-android-instrumented"
                val client = ClientSocket.allocate()
                client.open(port, 5.seconds)
                client.write(text.toReadBuffer(Charset.UTF8), 5.seconds)
                val response = client.read(5.seconds)
                val echoed = response.readString(response.remaining(), Charset.UTF8)
                assertEquals(text, echoed)

                client.close()
                serverDone.lock()
                server.close()
                serverJob.cancel()
            }
        }
}
