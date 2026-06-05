package com.ditchoom.socket.http3

/** A QPACK header field: a [name] and its [value] (RFC 9204). */
data class QpackHeaderField(
    val name: String,
    val value: String,
)

/**
 * The QPACK static table (RFC 9204 Appendix A) — 99 fixed entries, indices
 * 0..98, transcribed verbatim from the RFC including its exact casing and
 * spacing (e.g. index 52 `text/html; charset=utf-8` has a space after the
 * semicolon while index 54 `text/plain;charset=utf-8` does not; the
 * access-control-allow-credentials values are the literal `FALSE`/`TRUE`).
 *
 * The table is read-only and shared across all connections. [findExact] and
 * [findName] support an encoder picking representations; both return the lowest
 * matching index, per the usual encoder convention.
 */
object QpackStaticTable {
    val entries: List<QpackHeaderField> =
        listOf(
            QpackHeaderField(":authority", ""), // 0
            QpackHeaderField(":path", "/"), // 1
            QpackHeaderField("age", "0"), // 2
            QpackHeaderField("content-disposition", ""), // 3
            QpackHeaderField("content-length", "0"), // 4
            QpackHeaderField("cookie", ""), // 5
            QpackHeaderField("date", ""), // 6
            QpackHeaderField("etag", ""), // 7
            QpackHeaderField("if-modified-since", ""), // 8
            QpackHeaderField("if-none-match", ""), // 9
            QpackHeaderField("last-modified", ""), // 10
            QpackHeaderField("link", ""), // 11
            QpackHeaderField("location", ""), // 12
            QpackHeaderField("referer", ""), // 13
            QpackHeaderField("set-cookie", ""), // 14
            QpackHeaderField(":method", "CONNECT"), // 15
            QpackHeaderField(":method", "DELETE"), // 16
            QpackHeaderField(":method", "GET"), // 17
            QpackHeaderField(":method", "HEAD"), // 18
            QpackHeaderField(":method", "OPTIONS"), // 19
            QpackHeaderField(":method", "POST"), // 20
            QpackHeaderField(":method", "PUT"), // 21
            QpackHeaderField(":scheme", "http"), // 22
            QpackHeaderField(":scheme", "https"), // 23
            QpackHeaderField(":status", "103"), // 24
            QpackHeaderField(":status", "200"), // 25
            QpackHeaderField(":status", "304"), // 26
            QpackHeaderField(":status", "404"), // 27
            QpackHeaderField(":status", "503"), // 28
            QpackHeaderField("accept", "*/*"), // 29
            QpackHeaderField("accept", "application/dns-message"), // 30
            QpackHeaderField("accept-encoding", "gzip, deflate, br"), // 31
            QpackHeaderField("accept-ranges", "bytes"), // 32
            QpackHeaderField("access-control-allow-headers", "cache-control"), // 33
            QpackHeaderField("access-control-allow-headers", "content-type"), // 34
            QpackHeaderField("access-control-allow-origin", "*"), // 35
            QpackHeaderField("cache-control", "max-age=0"), // 36
            QpackHeaderField("cache-control", "max-age=2592000"), // 37
            QpackHeaderField("cache-control", "max-age=604800"), // 38
            QpackHeaderField("cache-control", "no-cache"), // 39
            QpackHeaderField("cache-control", "no-store"), // 40
            QpackHeaderField("cache-control", "public, max-age=31536000"), // 41
            QpackHeaderField("content-encoding", "br"), // 42
            QpackHeaderField("content-encoding", "gzip"), // 43
            QpackHeaderField("content-type", "application/dns-message"), // 44
            QpackHeaderField("content-type", "application/javascript"), // 45
            QpackHeaderField("content-type", "application/json"), // 46
            QpackHeaderField("content-type", "application/x-www-form-urlencoded"), // 47
            QpackHeaderField("content-type", "image/gif"), // 48
            QpackHeaderField("content-type", "image/jpeg"), // 49
            QpackHeaderField("content-type", "image/png"), // 50
            QpackHeaderField("content-type", "text/css"), // 51
            QpackHeaderField("content-type", "text/html; charset=utf-8"), // 52
            QpackHeaderField("content-type", "text/plain"), // 53
            QpackHeaderField("content-type", "text/plain;charset=utf-8"), // 54
            QpackHeaderField("range", "bytes=0-"), // 55
            QpackHeaderField("strict-transport-security", "max-age=31536000"), // 56
            QpackHeaderField("strict-transport-security", "max-age=31536000; includesubdomains"), // 57
            QpackHeaderField("strict-transport-security", "max-age=31536000; includesubdomains; preload"), // 58
            QpackHeaderField("vary", "accept-encoding"), // 59
            QpackHeaderField("vary", "origin"), // 60
            QpackHeaderField("x-content-type-options", "nosniff"), // 61
            QpackHeaderField("x-xss-protection", "1; mode=block"), // 62
            QpackHeaderField(":status", "100"), // 63
            QpackHeaderField(":status", "204"), // 64
            QpackHeaderField(":status", "206"), // 65
            QpackHeaderField(":status", "302"), // 66
            QpackHeaderField(":status", "400"), // 67
            QpackHeaderField(":status", "403"), // 68
            QpackHeaderField(":status", "421"), // 69
            QpackHeaderField(":status", "425"), // 70
            QpackHeaderField(":status", "500"), // 71
            QpackHeaderField("accept-language", ""), // 72
            QpackHeaderField("access-control-allow-credentials", "FALSE"), // 73
            QpackHeaderField("access-control-allow-credentials", "TRUE"), // 74
            QpackHeaderField("access-control-allow-headers", "*"), // 75
            QpackHeaderField("access-control-allow-methods", "get"), // 76
            QpackHeaderField("access-control-allow-methods", "get, post, options"), // 77
            QpackHeaderField("access-control-allow-methods", "options"), // 78
            QpackHeaderField("access-control-expose-headers", "content-length"), // 79
            QpackHeaderField("access-control-request-headers", "content-type"), // 80
            QpackHeaderField("access-control-request-method", "get"), // 81
            QpackHeaderField("access-control-request-method", "post"), // 82
            QpackHeaderField("alt-svc", "clear"), // 83
            QpackHeaderField("authorization", ""), // 84
            QpackHeaderField("content-security-policy", "script-src 'none'; object-src 'none'; base-uri 'none'"), // 85
            QpackHeaderField("early-data", "1"), // 86
            QpackHeaderField("expect-ct", ""), // 87
            QpackHeaderField("forwarded", ""), // 88
            QpackHeaderField("if-range", ""), // 89
            QpackHeaderField("origin", ""), // 90
            QpackHeaderField("purpose", "prefetch"), // 91
            QpackHeaderField("server", ""), // 92
            QpackHeaderField("timing-allow-origin", "*"), // 93
            QpackHeaderField("upgrade-insecure-requests", "1"), // 94
            QpackHeaderField("user-agent", ""), // 95
            QpackHeaderField("x-forwarded-for", ""), // 96
            QpackHeaderField("x-frame-options", "deny"), // 97
            QpackHeaderField("x-frame-options", "sameorigin"), // 98
        )

    /** Number of entries in the static table (99). */
    val size: Int get() = entries.size

    /** The entry at [index]. @throws IllegalArgumentException if out of range. */
    fun entry(index: Int): QpackHeaderField {
        require(index in entries.indices) { "QPACK static table index out of range [0, ${entries.size - 1}]: $index" }
        return entries[index]
    }

    // Every (name, value) pair in the static table is unique, so associate is exact.
    private val exactIndex: Map<QpackHeaderField, Int> =
        entries.withIndex().associate { (index, field) -> field to index }

    // Names repeat (e.g. :status, content-type); keep the lowest index per name.
    private val nameIndex: Map<String, Int> =
        entries
            .withIndex()
            .groupBy({ it.value.name }) { it.index }
            .mapValues { (_, indices) -> indices.min() }

    /** Lowest index whose name AND value both match, or null. */
    fun findExact(
        name: String,
        value: String,
    ): Int? = exactIndex[QpackHeaderField(name, value)]

    /** Lowest index whose name matches (any value), or null. */
    fun findName(name: String): Int? = nameIndex[name]
}
