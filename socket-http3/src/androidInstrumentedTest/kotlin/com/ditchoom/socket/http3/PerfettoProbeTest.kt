package com.ditchoom.socket.http3

import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.fail

class PerfettoProbeTest {
    @Test
    fun probe() {
        val mode = InstrumentationRegistry.getArguments().getString("probe") ?: "none"
        val blocked =
            Thread({
                Trace.beginSection("probe-blocked-wait")
                LockSupport.park(Any())
                Trace.endSection()
            }, "probe-blocked-thread")
        val busy =
            Thread({
                var x = 0L
                while (!Thread.currentThread().isInterrupted) x += 1
            }, "probe-busy-thread")
        blocked.start()
        busy.start()
        Thread.sleep(8_000)
        if (mode == "hang") blocked.join()
        if (mode == "fail") fail("deliberate failure for the Perfetto ring probe")
        busy.interrupt()
    }
}
