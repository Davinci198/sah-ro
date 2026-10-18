package com.sah.content

/**
 * Minimal JSON reader for the bundled assets.
 *
 * The project deliberately avoids new dependencies, and `org.json` is only
 * available on Android (which would make the content layer untestable on a plain
 * JVM), so this small recursive descent parser is used instead. It supports the
 * full JSON grammar for values; only what the assets need is exercised, which is
 * covered by `JsonTest` against the real `lessons.json`/`strings.json`.
 *
 * Parsed values map to: [Map]<String, Any?>, [List]<Any?>, [String], [Long],
 * [Double], [Boolean] and null.
 */
object Json {

    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("trailing content")
        return value
    }

    /** Parses and casts to an object, throwing [JsonException] on mismatch. */
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        return value as? Map<String, Any?>
            ?: throw JsonException("expected a JSON object at the top level")
    }

    private class Parser(private val source: String) {
        private var position = 0

        fun atEnd(): Boolean = position >= source.length

        fun fail(message: String): Nothing =
            throw JsonException("$message (offset $position)")

        fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (val c = source[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected '$c'")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = source[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val esc = source[position++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > source.length) fail("bad unicode escape")
                                val hex = source.substring(position, position + 4)
                                position += 4
                                builder.append(hex.toInt(16).toChar())
                            }
                            else -> fail("bad escape '\\$esc'")
                        }
                    }
                    else -> builder.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && source[position] in '0'..'9') position++
            var integral = true
            if (!atEnd() && source[position] == '.') {
                integral = false
                position++
                while (!atEnd() && source[position] in '0'..'9') position++
            }
            if (!atEnd() && (source[position] == 'e' || source[position] == 'E')) {
                integral = false
                position++
                if (!atEnd() && (source[position] == '+' || source[position] == '-')) position++
                while (!atEnd() && source[position] in '0'..'9') position++
            }
            val text = source.substring(start, position)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("bad number '$text'")
        }

        private fun literal(text: String, value: Any?): Any? {
            if (!source.startsWith(text, position)) fail("expected '$text'")
            position += text.length
            return value
        }

        private fun expect(c: Char) {
            if (atEnd() || source[position] != c) fail("expected '$c'")
            position++
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else source[position]
    }
}

// -- Convenience readers ------------------------------------------------------

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonObject(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.asJsonArray(): List<Any?>? = this as? List<Any?>

fun Any?.asString(): String? = this as? String

fun Any?.asInt(): Int? = when (this) {
    is Long -> toInt()
    is Double -> toInt()
    is String -> toIntOrNull()
    else -> null
}

fun Any?.asBoolean(): Boolean? = this as? Boolean

/** Reads [key] as a string, or null when absent/not a string. */
fun Map<String, Any?>.string(key: String): String? = this[key] as? String

fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
    is Long -> value.toInt()
    is Double -> value.toInt()
    else -> null
}

fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean

fun Map<String, Any?>.objectOrNull(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

fun Map<String, Any?>.arrayOrNull(key: String): List<Any?>? = this[key] as? List<Any?>

/** Reads [key] as a list of strings, skipping non-string entries. */
fun Map<String, Any?>.stringList(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

/** Reads [key] as a list of objects (each a string-keyed map). */
fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
