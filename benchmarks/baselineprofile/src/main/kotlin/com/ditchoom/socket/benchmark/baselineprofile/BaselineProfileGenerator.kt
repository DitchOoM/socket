package com.ditchoom.socket.benchmark.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal const val TARGET_PACKAGE = "com.ditchoom.socket.benchmark.app"

/**
 * Records the library methods :benchmark-android-app runs for its first requests (TCP round trips, a
 * QUIC handshake, stream round trips, stream-per-request). The app's build filters the result to
 * com.ditchoom.socket.** and distributeBaselineProfile splits it into the libraries' AARs.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun firstRequests() =
        rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            awaitWorkload()
        }
}

/** The app shows "done" (or "failed: ...") when its workload finishes; a failure fails the run. */
internal fun androidx.benchmark.macro.MacrobenchmarkScope.awaitWorkload() {
    val done = device.wait(Until.findObject(By.text("done")), 60_000)
    if (done == null) {
        val shown = device.findObject(By.pkg(TARGET_PACKAGE).depth(1))?.text
        error("the workload did not finish within 60 s (screen shows: $shown)")
    }
}
