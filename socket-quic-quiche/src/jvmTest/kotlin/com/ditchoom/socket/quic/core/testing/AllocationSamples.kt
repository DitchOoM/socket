package com.ditchoom.socket.quic.core.testing

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.nio.channels.DatagramChannel
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

/** Descriptors an allocation gate holds first, so its own sit above the JDK's `Integer` cache (-128..127). */
internal const val HELD_DESCRIPTORS = 160

/**
 * Runs [body] with [HELD_DESCRIPTORS] descriptors open (POSIX hands out the lowest free number, so the body's sockets
 * get numbers above 127 whatever the worker had open). The JDK selector's `fdToKey.get(fd)` boxes each such descriptor,
 * one `Integer` per ready key; without this a gate passes or fails by how many descriptors the worker holds (design 9.3).
 */
internal inline fun <T> withDescriptorsAbove127(body: () -> T): T {
    val held = List(HELD_DESCRIPTORS) { DatagramChannel.open() }
    try {
        return body()
    } finally {
        held.forEach { it.close() }
    }
}
