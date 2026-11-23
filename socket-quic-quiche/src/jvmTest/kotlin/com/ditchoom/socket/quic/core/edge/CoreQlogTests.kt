package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A core connection writes quiche's qlog where its capture names one, and a server's connections where the
 * `QUIC_QLOG_DIR` door names one; each file is JSON-SEQ (RFC 7464) whose every record parses as JSON, a header first.
 */
class CoreQlogTests {
    @Test
    fun aCoreClientAndItsCoreServerEachWriteAQlogAQlogParserAccepts() =
        runBlocking {
            skipOnMissingNativeLib(CoreQlogTests::class) {
                val dir = Files.createTempDirectory("core-qlog").toFile()
                val clientQlog = File(dir, "client.sqlog")
                val serverDir = File(dir, "server").apply { mkdirs() }
                val capture =
                    QuicTraceCapture(
                        captureFor = { QuicConnectionCapture(sink = TraceSink { }, qlog = QlogTarget.File(clientQlog.absolutePath)) },
                        qlog = QlogDeclaration.NotBudgeted,
                    )
                val accepted = CompletableDeferred<CoreConnection>()
                withQlogDir(serverDir) {
                    withCoreEchoServer(onAccepted = { accepted.complete(it) }) { server ->
                        val client = connectCore(server.port, options = coreTestOptions.copy(trace = capture))
                        val stream = client.connection.openStream()
                        val payload = BufferFactory.network().allocate(PAYLOAD).apply { repeat(PAYLOAD) { writeByte(7) } }
                        payload.resetForRead()
                        stream.write(payload, 10.seconds)
                        val back = stream.read(10.seconds)
                        if (back !is ReadResult.Data) fail("no echo: $back")
                        back.buffer.freeIfNeeded()
                        client.connection.close(QuicError.NoError)
                        freed(client.connection)
                    }
                }
                freed(accepted.await())

                assertQlog(clientQlog, "client")
                val serverQlogs = serverDir.listFiles { f -> f.name.endsWith(".sqlog") }.orEmpty()
                assertEquals(1, serverQlogs.size, "one qlog for the server's one connection: ${serverQlogs.map { it.name }}")
                assertTrue(serverQlogs[0].name.startsWith("quiche-server-"), serverQlogs[0].name)
                assertQlog(serverQlogs[0], "server")
            }
        }

    /** quiche flushes a qlog when it frees the connection, which the connection's scope ends after. */
    private suspend fun freed(connection: CoreConnection) = withTimeout(10.seconds) { connection.coroutineContext.job.join() }

    private fun assertQlog(
        file: File,
        vantage: String,
    ) {
        assertTrue(file.length() > 0, "$file was not written")
        val records =
            file
                .readText()
                .split(RECORD_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        assertTrue(records.size > 2, "$file holds a header and events: ${records.size} records")
        records.forEachIndexed { index, record -> JsonCheck(record).document("$file record $index") }
        val header = records.first()
        assertTrue("\"serialization_format\":\"JSON-SEQ\"" in header, "$file header: $header")
        assertTrue(
            "\"vantage_point\":{\"type\":\"$vantage\"" in header.replace(" ", ""),
            "$file header names the $vantage vantage: $header",
        )
        val events = records.drop(1)
        assertTrue(events.any { "\"name\":\"quic:packet_sent\"" in it }, "$file has sent packets")
        assertTrue(events.any { "\"name\":\"quic:packet_received\"" in it }, "$file has received packets")
    }

    /** The JVM's `QUIC_QLOG_DIR` seam: a process cannot set its own environment, the property is read first. */
    private inline fun <T> withQlogDir(
        dir: File,
        block: () -> T,
    ): T {
        val previous = System.getProperty(QLOG_DIR_PROPERTY)
        System.setProperty(QLOG_DIR_PROPERTY, dir.absolutePath)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(QLOG_DIR_PROPERTY) else System.setProperty(QLOG_DIR_PROPERTY, previous)
        }
    }

    private companion object {
        const val PAYLOAD = 64
        const val RECORD_SEPARATOR = '\u001e'
        const val QLOG_DIR_PROPERTY = "quic.qlog.dir"
    }
}

/** A strict RFC 8259 recognizer: [document] fails unless the whole text is exactly one JSON value. */
private class JsonCheck(
    private val text: String,
) {
    private var at = 0

    fun document(name: String) {
        try {
            value()
            space()
            if (at != text.length) bad("trailing text")
        } catch (e: IllegalArgumentException) {
            fail("$name is not JSON: ${e.message} at $at in ${text.take(200)}")
        }
    }

    private fun value() {
        space()
        when (peek()) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> word("true")
            'f' -> word("false")
            'n' -> word("null")
            else -> number()
        }
    }

    private fun obj() {
        at++
        space()
        if (peek() == '}') return run { at++ }
        while (true) {
            space()
            string()
            space()
            expect(':')
            value()
            space()
            when (next()) {
                ',' -> continue
                '}' -> return
                else -> bad("expected , or }")
            }
        }
    }

    private fun array() {
        at++
        space()
        if (peek() == ']') return run { at++ }
        while (true) {
            value()
            space()
            when (next()) {
                ',' -> continue
                ']' -> return
                else -> bad("expected , or ]")
            }
        }
    }

    private fun string() {
        expect('"')
        while (true) {
            val c = next()
            when {
                c == '"' -> return
                c == '\\' -> if (next() == 'u') repeat(4) { if (!next().isLetterOrDigit()) bad("bad \\u escape") }
                c < ' ' -> bad("control character in a string")
            }
        }
    }

    private fun number() {
        val start = at
        if (peek() == '-') at++
        while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
        if (at == start || text.substring(start, at).toDoubleOrNull() == null) bad("not a value")
    }

    private fun word(w: String) {
        if (!text.startsWith(w, at)) bad("expected $w")
        at += w.length
    }

    private fun expect(c: Char) {
        if (next() != c) bad("expected $c")
    }

    private fun space() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun peek(): Char = if (at < text.length) text[at] else bad("unexpected end")

    private fun next(): Char = peek().also { at++ }

    private fun bad(why: String): Nothing = throw IllegalArgumentException(why)
}
