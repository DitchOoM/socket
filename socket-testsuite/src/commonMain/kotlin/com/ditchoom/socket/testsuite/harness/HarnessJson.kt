package com.ditchoom.socket.testsuite.harness

/**
 * Minimal hand-rolled JSON field extraction for the two wire formats this package
 * speaks: the harness controller's `/describe` manifest and toxiproxy 2.x control
 * responses. Both are produced by code we control (or by toxiproxy, whose flat
 * response shape is stable), so a lightweight scanner beats dragging a JSON
 * dependency into a published main source set.
 *
 * Limitations (fine for both producers): brace balancing does not skip braces
 * inside string literals, and string extraction handles only simple `\x` escapes.
 * Neither format ever emits braces inside strings.
 */
internal object HarnessJson {
    /**
     * Return the balanced `{ … }` object that follows `"key":`, or null when the key
     * is absent. Occurrences of `"key"` whose value is not an object (e.g. the
     * `"echo":15000` *number* inside the toxiproxy block, vs. the `"echo":{…}`
     * scenario object) are skipped, so key names may repeat across nesting levels.
     */
    fun objectField(
        json: String,
        key: String,
    ): String? {
        val needle = "\"$key\""
        var i = 0
        while (true) {
            val k = json.indexOf(needle, i)
            if (k < 0) return null
            var j = k + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != ':') {
                i = k + needle.length
                continue
            }
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != '{') {
                i = k + needle.length
                continue
            }
            val start = j
            var depth = 0
            while (j < json.length) {
                when (json[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return json.substring(start, j + 1)
                    }
                }
                j++
            }
            return null // unbalanced — treat as absent
        }
    }

    /** First `"key":"value"` string value in [json], or null. */
    fun stringField(
        json: String,
        key: String,
    ): String? = stringValues(json, key).firstOrNull()

    /** First `"key":<int>` numeric value in [json], or null. */
    fun intField(
        json: String,
        key: String,
    ): Int? {
        val needle = "\"$key\""
        var i = 0
        while (true) {
            val k = json.indexOf(needle, i)
            if (k < 0) return null
            var j = k + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != ':') {
                i = k + needle.length
                continue
            }
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            val start = j
            if (j < json.length && json[j] == '-') j++
            while (j < json.length && json[j].isDigit()) j++
            val parsed = json.substring(start, j).toIntOrNull()
            if (parsed != null) return parsed
            i = k + needle.length
        }
    }

    /**
     * Extract all values of `"key":"value"` pairs from a JSON blob. Ported from the
     * root module's `Toxiproxy.extractJsonStringField` — sufficient for toxiproxy
     * 2.12 responses (no nested strings share the scanned key).
     */
    fun stringValues(
        json: String,
        key: String,
    ): List<String> {
        val needle = "\"$key\""
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val k = json.indexOf(needle, i)
            if (k < 0) break
            var j = k + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != ':') {
                i = k + needle.length
                continue
            }
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != '"') {
                i = k + needle.length
                continue
            }
            j++
            val sb = StringBuilder()
            while (j < json.length && json[j] != '"') {
                if (json[j] == '\\' && j + 1 < json.length) {
                    sb.append(json[j + 1])
                    j += 2
                } else {
                    sb.append(json[j])
                    j++
                }
            }
            out += sb.toString()
            i = j + 1
        }
        return out
    }
}
