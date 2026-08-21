package com.ditchoom.socket.quic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guard for `libs/quiche/include/quiche.h` — the **hand-curated** header the JNI shim and both K/N
 * cinterops compile against.
 *
 * Its own comments say the build "does not overwrite" it, which is deliberate: the caller-clock FFI
 * declarations live there and a wholesale copy from the clone would erase them. The cost of that
 * choice is that the file drifts from the quiche it is supposed to describe, silently, and a header
 * that disagrees with its library is not a style problem:
 *
 *  - **Declared but absent.** Found 2026-08-21: this header declared
 *    `quiche_conn_retired_scid_next`, which quiche 0.29.3 does not export. A declaration with no
 *    symbol behind it compiles cleanly and resolves to nothing — and K/N links some paths with
 *    `--unresolved-symbols=ignore-in-object-files`, so the failure surfaces at runtime as a symbol
 *    lookup error, far from the cause.
 *  - **Absent but needed.** The same drift omitted `quiche_conn_retired_scid_iter`, which the library
 *    *does* export. That one is merely annoying — the cinterop cannot see the symbol, so the build
 *    fails loudly — but it is the same divergence.
 *
 * Both directions were live at once, three declarations apart, in the header that decides what four
 * FFI backends can call. This is the same family as #388 (`TransportParams` missing `#[repr(C)]`):
 * our description of quiche disagreeing with quiche.
 *
 * JVM-only because it reads repository files rather than exercising a binding — the same idiom as
 * [CinteropQuicheApiDriftGuardTest], which guards the other duplicated FFI surface.
 */
class VendoredQuicheHeaderSyncTest {
    private companion object {
        const val VENDORED = "libs/quiche/include/quiche.h"

        /** Every `quiche_*` name declared as a function in [header]. */
        fun declarations(header: File): Set<String> =
            Regex("""^[A-Za-z_][A-Za-z0-9_ *]*?\b(quiche_[a-z0-9_]+)\s*\(""", RegexOption.MULTILINE)
                .findAll(header.readText())
                .map { it.groupValues[1] }
                .toSortedSet()
    }

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("socket-quic-quiche/$relative"))
            .firstOrNull { it.isFile }
            ?: error("Cannot find $relative from ${File(".").absolutePath} — has the file moved?")

    /**
     * The version pinned in `gradle/libs.versions.toml`. Read rather than hardcoded, so a quiche bump
     * cannot leave this guard silently checking the old one.
     */
    private fun pinnedVersion(): String {
        val toml =
            listOf(File("gradle/libs.versions.toml"), File("../gradle/libs.versions.toml"))
                .firstOrNull { it.isFile }
                ?: error("Cannot find gradle/libs.versions.toml from ${File(".").absolutePath}")
        return Regex("""^quiche\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(toml.readText())
            ?.groupValues
            ?.get(1)
            ?: error("No `quiche = \"…\"` pin in ${toml.absolutePath}")
    }

    /**
     * The **pinned** quiche's own header, from the clone the build makes — or `null` when this lane
     * never built it (the natives fan-out downloads prebuilt archives instead, so `build/quiche/` can
     * be legitimately absent).
     *
     * Selected by version, never "whichever directory is first": `build/quiche/` accumulates every
     * version this tree has ever built (0.28.0, 0.29.2 and 0.29.3 were all present when this was
     * written), and checking the vendored header against a stale clone is worse than not checking —
     * it reports drift that is really just the old API. Same hazard as the stale `.built-*` markers
     * in #392.
     */
    private fun pinnedHeader(): File? {
        val version = pinnedVersion()
        return listOf(File("build/quiche"), File("socket-quic-quiche/build/quiche"))
            .firstOrNull { it.isDirectory }
            ?.let { File(it, "quiche-$version/quiche/include/quiche.h") }
            ?.takeIf { it.isFile }
    }

    @Test
    fun everyQuicheSymbolTheBindingsCallIsDeclaredInTheVendoredHeader() {
        val vendored = declarations(repoFile(VENDORED))
        val sources =
            listOf("src/commonMain", "src/commonJvmMain", "src/jvm21Main", "src/appleMain", "src/linuxMain", "src/jni")
                .mapNotNull { runCatching { repoFile(it) }.getOrNull() ?: File("socket-quic-quiche/$it").takeIf { d -> d.isDirectory } }
                .flatMap { it.walkTopDown().filter { f -> f.isFile && (f.extension == "kt" || f.extension == "c") }.toList() }

        // Names our bindings actually reference, minus the ones that are ours rather than quiche's:
        // the caller-clock patch symbols are declared in this header too, so they are in `vendored`
        // and need no special case, but a *missing* one must still fail here.
        val called =
            sources
                .flatMap { Regex("""quiche_[a-z0-9_]+""").findAll(it.readText()).map { m -> m.value } }
                .toSortedSet()

        val undeclared = called - vendored - TYPES_AND_MACROS
        assertTrue(
            undeclared.isEmpty(),
            "These quiche symbols are referenced by our bindings but NOT declared in $VENDORED:\n" +
                undeclared.joinToString("\n") { "  $it" } +
                "\n\nThe JNI shim and both cinterops compile against that header. A symbol missing from " +
                "it is invisible to the K/N bindings even when libquiche exports it. Add the " +
                "declaration, copying it verbatim from the pinned quiche's own header.",
        )
    }

    @Test
    fun theVendoredHeaderDeclaresNothingThePinnedQuicheDoesNotExport() {
        val pinned = pinnedHeader()
        if (pinned == null) {
            // Loud, not silent: this lane cannot run the check, and saying so beats a green tick that
            // means nothing (#359's lesson).
            println(
                "SKIPPED theVendoredHeaderDeclaresNothingThePinnedQuicheDoesNotExport: no quiche clone " +
                    "under build/quiche — this lane consumed prebuilt natives instead of building them. " +
                    "The check runs on any lane that builds quiche from source.",
            )
            return
        }

        val extras = declarations(repoFile(VENDORED)) - declarations(pinned) - OURS
        assertTrue(
            extras.isEmpty(),
            "$VENDORED declares symbols the pinned quiche (${pinnedVersion()}) does not export:\n" + extras.joinToString("\n") { "  $it" } +
                "\n\nA declaration with no symbol behind it compiles and then resolves to nothing — the " +
                "exact shape of the `quiche_conn_retired_scid_next` drift found in #437. Delete it, or " +
                "if quiche renamed the API, replace it with the current spelling.",
        )
    }
}

/** Type names and macros that appear as `quiche_*` but are never function declarations. */
private val TYPES_AND_MACROS =
    setOf(
        "quiche_config",
        "quiche_conn",
        "quiche_connection_id_iter",
        "quiche_recv_info",
        "quiche_send_info",
        "quiche_path_stats",
        "quiche_stats",
        "quiche_stream_iter",
        "quiche_socket_addr_iter",
        "quiche_h3_config",
        "quiche_h3_conn",
        "quiche_h3_event",
        "quiche_h3_header",
        "quiche_error",
        "quiche_path_event",
        "quiche_transport_params",
    )

/** Declarations this repository adds on purpose — the caller-clock patch's FFI, absent upstream. */
private val OURS =
    setOf(
        "quiche_set_virtual_time_nanos",
        "quiche_clear_virtual_time",
    )
