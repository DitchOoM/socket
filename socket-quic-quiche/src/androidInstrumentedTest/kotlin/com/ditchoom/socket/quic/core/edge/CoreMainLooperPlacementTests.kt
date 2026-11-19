package com.ditchoom.socket.quic.core.edge

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.socket.quic.QuicError
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A caller on Android's main thread makes no system call in place, whatever dispatcher it reaches the core through: the
 * main looper's thread is a main thread even under `runBlocking`, whose dispatcher is not a main dispatcher.
 */
@RunWith(AndroidJUnit4::class)
class CoreMainLooperPlacementTests {
    @Test
    fun aCallerOnTheMainLooperThroughAnotherDispatcherSendsNothingInPlace() =
        runBlocking {
            withEchoServer { port ->
                val recorder = arrayOfNulls<RecordingIo>(1)
                val client = connectCore(port) { RecordingIo(it).also { io -> recorder[0] = io } }
                val io = recorder[0] ?: error("the connect wrapped no socket")
                val stream = client.connection.openStream()
                val payload = Random(3).nextBytes(ECHO_BYTES)
                val main = Looper.getMainLooper().thread.name
                val echoed = ArrayList<ByteArray>(ECHOES)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    runBlocking { repeat(ECHOES) { echoed.add(echoOver(stream, payload)) } }
                }
                echoed.forEachIndexed { i, got -> assertContentEquals(payload, got, "echo $i") }
                val sends = io.sendsByThread()
                assertTrue(main !in sends, "the main thread sent in place: $sends")
                client.connection.close(QuicError.NoError)
            }
        }

    private companion object {
        const val ECHOES = 50
        const val ECHO_BYTES = 512
    }
}
