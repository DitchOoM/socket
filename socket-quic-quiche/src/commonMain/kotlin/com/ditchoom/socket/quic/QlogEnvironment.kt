@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogTarget
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The `QUIC_QLOG_DIR` door's directories: one [QlogDirectory] per path for the life of the process,
 * so every connection written into a directory shares its budget, and the qlog an earlier process left
 * there counts against it too.
 *
 * The budget is [QlogBudget.forWalk] for the walk the directory's server serves — `QUIC_QLOG_WALK_MINUTES`,
 * `QUIC_QLOG_WALK_ECHO_MS` and `QUIC_QLOG_WALK_CLIENTS` (JVM: `quic.qlog.walk.minutes`,
 * `quic.qlog.walk.echo.ms`, `quic.qlog.walk.clients`), each defaulting to the device rig's own walk.
 * Every line the directory reports goes to stdout and to [NOTES] inside the directory, so a pull of the
 * qlog carries its own record of what was dropped and under which budget.
 */
internal object QlogEnvironment {
    const val NOTES = "qlog-budget.log"

    private val directories = AtomicReference<Map<String, QlogDirectory>>(emptyMap())

    /** The budgeted target for the connection named [name] in the qlog directory [path]. */
    fun target(
        path: String,
        name: String,
    ): QlogTarget = QlogTarget.Budgeted(directory(path), name)

    fun directory(path: String): QlogDirectory {
        while (true) {
            val current = directories.load()
            val known = current[path]
            if (known != null) return known
            val settings = WalkSettings.read()
            val created = QlogDirectory(path, settings.budget) { event -> note(path, event.line) }
            if (directories.compareAndSet(current, current + (path to created))) {
                note(path, settings.budget.line)
                settings.ignored.forEach { note(path, it) }
                created.adopt(listQlogFiles(path))
                return created
            }
        }
    }

    /** One line to stdout and to the directory's [NOTES]. */
    fun note(
        path: String,
        line: String,
    ) {
        println("[qlog] $line")
        appendNote(path, line)
    }

    /** One line to the directory's [NOTES] only, stamped with the wall clock. */
    fun appendNote(
        path: String,
        line: String,
    ) {
        appendQlogNote("${path.trimEnd('/')}/$NOTES", "${Clock.System.now()} $line")
    }

    /** The walk a directory's budget is derived for, and every setting that could not be read as one. */
    private class WalkSettings(
        val budget: QlogBudget,
        val ignored: List<String>,
    ) {
        companion object {
            const val DEFAULT_MINUTES = 4500
            const val DEFAULT_ECHO_MS = 250
            const val DEFAULT_CLIENTS = 2

            fun read(): WalkSettings {
                val ignored = mutableListOf<String>()

                fun number(
                    property: String,
                    variable: String,
                    default: Int,
                ): Int {
                    val setting = environmentSetting(property, variable)
                    if (setting !is EnvironmentSetting.Value) return default
                    val parsed = setting.text.toIntOrNull()
                    if (parsed != null && parsed > 0) return parsed
                    ignored += "QLOG-SETTING-IGNORED $variable=${setting.text} — not a positive whole number; using $default"
                    return default
                }
                val minutes = number("quic.qlog.walk.minutes", "QUIC_QLOG_WALK_MINUTES", DEFAULT_MINUTES)
                val echoMs = number("quic.qlog.walk.echo.ms", "QUIC_QLOG_WALK_ECHO_MS", DEFAULT_ECHO_MS)
                val clients = number("quic.qlog.walk.clients", "QUIC_QLOG_WALK_CLIENTS", DEFAULT_CLIENTS)
                return WalkSettings(QlogBudget.forWalk(minutes, echoMs.milliseconds, clients), ignored)
            }
        }
    }
}
