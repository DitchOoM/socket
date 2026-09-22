package com.ditchoom.socket.testkit.walk

import com.ditchoom.socket.testkit.echo.SilenceWatchdog
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * One lane's progress as the heartbeat sees it: the lane's own coroutine publishes its attempt number
 * and how far its echo loop has gone, and the heartbeat beats this lane's own [SilenceWatchdog]
 * against that. One per lane, because a count summed across lanes keeps moving while one lane
 * stands still.
 *
 * Each field has one writer: the lane's coroutine calls [nextAttempt], [connected] and
 * [progressed]; the heartbeat alone calls [beat].
 */
public class LaneWatch(
    public val lane: WalkLane,
    quietBeatsBeforeAlarm: Int,
    beatInterval: Duration,
) {
    private val watchdog = SilenceWatchdog(quietBeatsBeforeAlarm, beatInterval)

    /** The lane's current connection attempt, 1-based; 0 before the first. */
    @Volatile
    public var attempt: Int = 0
        private set

    /** Reads the lane's echo loops have completed across every connection: what [beat] compares. */
    @Volatile
    public var loopTicks: Int = 0
        private set

    private var ticksAtConnect = 0

    public fun nextAttempt(): Int {
        attempt += 1
        return attempt
    }

    /** A connection came up; its reads count on from everything the lane did before it. */
    public fun connected() {
        ticksAtConnect = loopTicks
    }

    /** The current connection's session has completed [exchanges] reads. */
    public fun progressed(exchanges: Int) {
        loopTicks = ticksAtConnect + exchanges
    }

    public fun beat(): SilenceWatchdog.Beat = watchdog.beat(loopTicks)
}
