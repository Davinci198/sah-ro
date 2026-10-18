package com.sah.content

/**
 * Small CSV reader for the bundled puzzle and opening tables. Handles quoted
 * fields (including embedded commas and newlines, and `""` escapes), CRLF input
 * and a leading UTF-8 BOM.
 */
object Csv {

    /** Splits [text] into rows of fields; blank trailing rows are dropped. */
    fun rows(text: String): List<List<String>> {
        val source = text.removePrefix("\uFEFF")
        val result = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < source.length) {
            val c = source[index]
            if (inQuotes) {
                if (c == '"') {
                    if (index + 1 < source.length && source[index + 1] == '"') {
                        field.append('"')
                        index += 2
                    } else {
                        inQuotes = false
                        index++
                    }
                } else {
                    field.append(c)
                    index++
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        index++
                    }
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        index++
                    }
                    '\r' -> index++
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        result.add(row)
                        row = ArrayList()
                        index++
                    }
                    else -> {
                        field.append(c)
                        index++
                    }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            result.add(row)
        }
        return result
    }

    /**
     * Reads a header row and returns one map per record, keyed by column name.
     * Rows shorter than the header keep empty strings for the missing columns;
     * rows longer than the header are ignored. A final row without a trailing
     * newline is still returned.
     */
    fun records(text: String): List<Map<String, String>> {
        val rows = rows(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        val result = ArrayList<Map<String, String>>(rows.size - 1)
        for (i in 1 until rows.size) {
            val cells = rows[i]
            if (cells.isEmpty()) continue
            val record = LinkedHashMap<String, String>(header.size)
            for (column in header.indices) {
                record[header[column]] = cells.getOrElse(column) { "" }
            }
            result.add(record)
        }
        return result
    }

    /** Serializes a field, quoting it when it contains a comma, quote or newline. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
