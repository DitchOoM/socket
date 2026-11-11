package com.ditchoom.socket.benchmark.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the shipped baseline profile buys: a cold start of :benchmark-android-app through its first TCP
 * round trip, QUIC handshake and first QUIC round trip. The app calls reportFullyDrawn() right after
 * those, so timeToFullDisplayMs is that span; it is measured with no AOT compilation at all versus
 * with the baseline profile installed. The app ships no profile of its own, only the libraries' merged
 * ones, so the difference is what this library's AARs contribute. Library code is a small part of a
 * real app's startup, so expect a modest effect; the comparison says how modest.
 */
@RunWith(AndroidJUnit4::class)
class FirstRequestBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun withoutProfile() = measure(CompilationMode.None())

    @Test
    fun withBaselineProfile() = measure(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measure(mode: CompilationMode) =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = 15,
        ) {
            pressHome()
            startActivityAndWait()
            awaitWorkload()
        }
}
