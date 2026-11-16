package com.ditchoom.socket.quic.core.testing

import android.util.Log

/**
 * A failed core test's flight recordings go to logcat under [FLIGHT_TAG]: the instrumented test app is uninstalled when
 * the run ends, taking any file it wrote, while the run's logcat is kept. Each line is `<path><TAB><trace line>`;
 * `scripts/android-core-flight.sh` rebuilds the files from it.
 */
internal fun flightDirectory(): String = "core-flight"

internal fun makeDirectories(path: String): Boolean = true

/** Logs [text] line by line under [FLIGHT_TAG], each line tagged with [path]; false when logging was refused. */
internal fun writeTextFile(
    path: String,
    text: String,
): Boolean {
    var written = true
    for (line in text.lineSequence()) {
        if (line.isEmpty()) continue
        written = Log.i(FLIGHT_TAG, "$path\t$line") > 0 && written
    }
    return written
}

private const val FLIGHT_TAG = "core-flight"
