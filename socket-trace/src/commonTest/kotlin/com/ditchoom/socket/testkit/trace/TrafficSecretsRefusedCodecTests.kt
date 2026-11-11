package com.ditchoom.socket.testkit.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/** The `TRAFFIC_SECRETS_REFUSED` line survives the text boundary a walk's trace file crosses. */
class TrafficSecretsRefusedCodecTests {
    private val event = TraceEvent.TrafficSecretsRefused(2_000_000L.nanoseconds, "/data/user/0/app/files/keys/conn-0007.keys")

    @Test
    fun theLineIsV1TimestampTrafficSecretsRefusedPath() {
        assertEquals("v1 2000000 TRAFFIC_SECRETS_REFUSED /data/user/0/app/files/keys/conn-0007.keys", event.toString())
    }

    @Test
    fun itRoundTripsAndIsAnObservation() {
        assertEquals(event, TraceEvent.parse(event.toString()))
        assertTrue(!event.isInput, "a local filesystem fact is never replayed as a network input")
    }
}
