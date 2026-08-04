package com.ditchoom.socket.udp

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * "A send either delivers or reports" on real Node `dgram` sockets — the one backend that already
 * reported send failures (its `dgram.send` callback raises [SendFailedException]), so this leg pins
 * behavior that already holds. See [assertSendNeverSilentlyDrops].
 */
@OptIn(DelicateCoroutinesApi::class)
class SendVisibilityTests {
    @Test
    fun sendNeverSilentlyDrops() =
        GlobalScope.promise {
            withTimeout(60_000) { assertSendNeverSilentlyDrops(this, hostLoopback) }
        }

    @Test
    fun oversizedSendReportsTooLarge() =
        GlobalScope.promise {
            withTimeout(60_000) { assertOversizedSendReportsTooLarge(this) }
        }

    @Test
    fun closeWithParkedReceiveYieldsClosed() =
        GlobalScope.promise {
            withTimeout(60_000) { assertCloseWithParkedReceiveYieldsClosed(this) }
        }
}
