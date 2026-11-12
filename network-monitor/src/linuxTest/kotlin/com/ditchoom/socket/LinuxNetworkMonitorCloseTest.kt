package com.ditchoom.socket

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LinuxNetworkMonitorCloseTest {
    /**
     * Closing a descriptor does not wake a thread blocked in `recv` on it, and `shutdown(2)` is refused by netlink
     * sockets (`ENOTSUP`), so a close that only closes leaves the reader parked until the next netlink event, which
     * on a quiet host may never come. The reader must have left once `close()` returns control, with no event sent.
     */
    @Test
    fun closeEndsTheNetlinkReaderWithoutAnyNetlinkEvent() =
        runBlocking {
            val monitor = LinuxNetworkMonitor()
            // Let the reader reach its blocking wait; a reader not yet started could not show the defect.
            delay(500.milliseconds)
            monitor.close()
            assertNotNull(
                withTimeoutOrNull(5.seconds) { monitor.reader.join() },
                "the netlink reader was still parked 5 s after close() with no netlink event arriving",
            )
        }
}
