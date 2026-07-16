package com.ditchoom.socket.quic.trace.tools

import com.ditchoom.socket.quic.trace.TraceEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TraceDeobfuscatorTest {
    // --- Rewrite logic, R8-free: a fake resolver proves ONLY the STATE/ERROR FQN tokens change. ---

    private val prefixing = TraceDeobfuscator { "orig.$it" }

    @Test
    fun state_name_is_remapped() {
        val event = TraceDeobfuscator(::deobfIdentityToOriginal).deobfuscate(TraceEvent.State(1L, "a.b.c", "h3"))
        assertEquals(TraceEvent.State(1L, "com.ditchoom.socket.quic.QuicConnectionState.Established", "h3"), event)
    }

    @Test
    fun error_type_is_remapped_but_message_untouched() {
        val event = prefixing.deobfuscate(TraceEvent.Error(2L, "x.y.Z", "ENETDOWN: down"))
        assertEquals(TraceEvent.Error(2L, "orig.x.y.Z", "ENETDOWN: down"), event)
    }

    @Test
    fun non_class_events_pass_through_untouched() {
        val dgram = TraceEvent.DgramOut(3L, 3, null, "aabbcc")
        assertEquals(dgram, prefixing.deobfuscate(dgram))
        val stats = TraceEvent.NetAvail(4L, com.ditchoom.socket.NetworkAvailability.UNAVAILABLE)
        assertEquals(stats, prefixing.deobfuscate(stats))
    }

    @Test
    fun deobfuscateAll_rewrites_only_state_and_error_lines_and_preserves_order() {
        val trace =
            listOf(
                TraceEvent.State(0L, "a.b.c", "h3").toString(),
                TraceEvent.DgramOut(1L, 2, null, "beef").toString(),
                TraceEvent.Error(2L, "x.y.Z", "boom").toString(),
            )
        val out = prefixing.deobfuscateAll(trace)
        assertEquals(
            listOf(
                TraceEvent.State(0L, "orig.a.b.c", "h3").toString(),
                TraceEvent.DgramOut(1L, 2, null, "beef").toString(),
                TraceEvent.Error(2L, "orig.x.y.Z", "boom").toString(),
            ),
            out,
        )
    }

    // --- The real R8 wiring: a fixture mapping.txt drives obfuscated → original resolution. ---

    private val mapping =
        """
        com.example.MyException -> x.y.z:
        com.ditchoom.socket.quic.QuicConnectionState${'$'}Established -> a.b:
        """.trimIndent()

    @Test
    fun r8_resolves_a_mapped_error_type_to_its_original_name() {
        val deobf = TraceDeobfuscator.fromMapping(mapping)
        val event = deobf.deobfuscate(TraceEvent.Error(1L, "x.y.z", "boom")) as TraceEvent.Error
        assertEquals("com.example.MyException", event.type)
        assertEquals("boom", event.message)
    }

    @Test
    fun r8_resolves_a_mapped_state_name_even_when_nested() {
        val deobf = TraceDeobfuscator.fromMapping(mapping)
        val event = deobf.deobfuscate(TraceEvent.State(1L, "a.b", "h3")) as TraceEvent.State
        // R8 may render the nested class with '.' or '$'; assert the meaningful parts, not the separator.
        assertContains(event.name, "QuicConnectionState")
        assertContains(event.name, "Established")
    }

    @Test
    fun r8_passes_through_an_unmapped_name() {
        val deobf = TraceDeobfuscator.fromMapping(mapping)
        val event = deobf.deobfuscate(TraceEvent.Error(1L, "com.unmapped.Boom", "x")) as TraceEvent.Error
        assertEquals("com.unmapped.Boom", event.type)
    }

    @Test
    fun handles_a_plain_proguard_mapping_with_members_and_repackaging() {
        // A JVM app shrunk with ProGuard/R8 produces a header-less mapping.txt with member lines and
        // repackaged (moved) class names — retrace reads the same grammar R8 does, so the tool covers
        // the JVM case, not just Android. Class-name resolution must ignore the member lines.
        val proguard =
            """
            com.acme.server.QuicHandler -> a.a.a:
                int retries -> a
                void handle() -> a
            com.acme.net.LinkException -> b.c.d:
            """.trimIndent()
        val deobf = TraceDeobfuscator.fromMapping(proguard)
        assertEquals(
            "com.acme.net.LinkException",
            (deobf.deobfuscate(TraceEvent.Error(1L, "b.c.d", "down")) as TraceEvent.Error).type,
        )
        assertEquals(
            "com.acme.server.QuicHandler",
            (deobf.deobfuscate(TraceEvent.State(1L, "a.a.a", "h3")) as TraceEvent.State).name,
        )
    }

    @Test
    fun r8_passes_through_the_non_class_fallback_token() {
        val deobf = TraceDeobfuscator.fromMapping(mapping)
        // The recorder's "Unknown" fallback isn't a valid class type name — must not throw, just pass through.
        val event = deobf.deobfuscate(TraceEvent.State(1L, "Unknown", null)) as TraceEvent.State
        assertEquals("Unknown", event.name)
    }

    private fun deobfIdentityToOriginal(obf: String): String =
        if (obf == "a.b.c") "com.ditchoom.socket.quic.QuicConnectionState.Established" else obf
}
