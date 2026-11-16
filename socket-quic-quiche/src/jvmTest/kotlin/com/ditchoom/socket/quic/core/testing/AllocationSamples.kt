package com.ditchoom.socket.quic.core.testing

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path

/**
 * The allocation samples in a dumped recording, on the threads [onThread] accepts. A dump also holds what every other
 * recording running in the JVM took over the same time (a CI lane's JFR ring records thread parks, sleeps and more),
 * so the events are chosen by type: only an allocation sample has `objectClass` and `weight`.
 */
internal fun allocationSamples(
    dump: Path,
    onThread: (String) -> Boolean,
): List<RecordedEvent> =
    RecordingFile.readAllEvents(dump).filter { event ->
        event.eventType.name == ALLOCATION_SAMPLE && event.thread?.javaName?.let(onThread) == true
    }

internal const val ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample"
